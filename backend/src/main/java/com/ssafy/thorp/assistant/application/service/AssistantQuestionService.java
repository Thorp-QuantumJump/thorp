package com.ssafy.thorp.assistant.application.service;

import com.ssafy.thorp.assistant.application.command.AskQuestionCommand;
import com.ssafy.thorp.assistant.application.port.AssistantModelPort;
import com.ssafy.thorp.assistant.application.port.AssistantSettings;
import com.ssafy.thorp.assistant.application.port.EvidenceBuilder;
import com.ssafy.thorp.assistant.application.result.AssistantAnswer;
import com.ssafy.thorp.assistant.application.result.Deadline;
import com.ssafy.thorp.assistant.application.result.EvidenceBundle;
import com.ssafy.thorp.assistant.application.result.PageContext;
import com.ssafy.thorp.assistant.application.result.PageKind;
import com.ssafy.thorp.common.exception.BusinessException;
import com.ssafy.thorp.common.exception.ErrorCode;
import com.ssafy.thorp.result.application.service.PublicResultService;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * 질문 하나의 흐름(spec 015). 순서가 곧 응답 코드의 우선순위다: 플래그(404) → 요청 검증(400) → 한도(429) → 페이지 판정(400/404/410) →
 * 근거 빌더(409) → 도구 루프 → 답변 검증·치환.
 *
 * <p>플래그·토큰 판정이 모두 끝난 뒤에만 모델을 부른다(FR-005, SC-004). 질문·답변 원문은 저장하지 않고 로그에도 남기지 않는다(FR-014).
 */
@Service
public class AssistantQuestionService {
    private static final Logger log = LoggerFactory.getLogger(AssistantQuestionService.class);

    private final AssistantSettings settings;
    private final AssistantCapabilities capabilities;
    private final AssistantRateLimiter limiter;
    private final PublicResultService publicResults;
    private final ObjectProvider<EvidenceBuilder> builders;
    private final ObjectProvider<AssistantModelPort> model;
    private final ToolLoop loop;
    private final AnswerValidator validator;
    private final Clock clock;

    public AssistantQuestionService(
            AssistantSettings settings,
            AssistantCapabilities capabilities,
            AssistantRateLimiter limiter,
            PublicResultService publicResults,
            ObjectProvider<EvidenceBuilder> builders,
            ObjectProvider<AssistantModelPort> model,
            ToolLoop loop,
            AnswerValidator validator,
            Clock clock) {
        this.settings = settings;
        this.capabilities = capabilities;
        this.limiter = limiter;
        this.publicResults = publicResults;
        this.builders = builders;
        this.model = model;
        this.loop = loop;
        this.validator = validator;
        this.clock = clock;
    }

    public AssistantAnswer ask(AskQuestionCommand command) {
        if (!capabilities.enabled()) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        }
        PageKind kind = parseKind(command.kind());
        if (!capabilities.supports(kind)) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        }
        if (command.extraContextKeys() != null && !command.extraContextKeys().isEmpty()) {
            throw new BusinessException(
                    ErrorCode.INVALID_INPUT,
                    "context 에는 " + kind + " 의 식별자 하나만 둡니다: " + command.extraContextKeys());
        }
        String question = command.question() == null ? "" : command.question().strip();
        if (question.isEmpty() || question.length() > settings.maxQuestionLength()) {
            throw new BusinessException(
                    ErrorCode.INVALID_INPUT,
                    "질문은 공백을 제외하고 1~" + settings.maxQuestionLength() + "자입니다");
        }
        String token = command.publicToken();
        if (token == null || token.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "publicToken 이 필요합니다");
        }
        limiter.admit(command.clientIp(), token);
        PageContext context = new PageContext(kind, token);
        // 시간 예산은 여기서부터 센다 — 근거 팩 만들기도 그 안이다(FR-012).
        Deadline deadline = new Deadline(clock, settings.totalBudget());
        // 형식 → 존재 → 만료. 실패하면 모델을 부르지 않는다.
        publicResults.resolve(token);
        EvidenceBundle bundle =
                builders.orderedStream()
                        .filter(builder -> builder.supports(kind))
                        .findFirst()
                        .map(builder -> builder.build(context))
                        .orElse(EvidenceBundle.empty());

        long started = System.nanoTime();
        ToolLoop.Outcome outcome = loop.run(model.getObject(), question, bundle, deadline);
        AnswerValidator.Rendered rendered = validator.render(outcome.draft(), outcome.facts());
        String answer = rendered.answer();
        if (bundle.partialUntil() != null) {
            answer = "이 결과는 " + bundle.partialUntil() + "까지의 부분 결과입니다. " + answer;
        }
        log.info(
                "assistant answered: kind={} status={} rounds={} facts={} latencyMs={}",
                kind,
                rendered.status(),
                outcome.rounds(),
                outcome.facts().size(),
                (System.nanoTime() - started) / 1_000_000);
        return new AssistantAnswer(rendered.status(), answer, rendered.evidence(), kind);
    }

    private static PageKind parseKind(String kind) {
        try {
            return PageKind.valueOf(kind == null ? "" : kind);
        } catch (IllegalArgumentException exc) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "알 수 없는 화면 종류입니다");
        }
    }
}
