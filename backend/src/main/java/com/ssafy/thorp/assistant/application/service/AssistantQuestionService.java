package com.ssafy.thorp.assistant.application.service;

import com.ssafy.thorp.assistant.application.command.AskQuestionCommand;
import com.ssafy.thorp.assistant.application.port.AssistantModelPort;
import com.ssafy.thorp.assistant.application.port.AssistantSettings;
import com.ssafy.thorp.assistant.application.port.EvidenceBuilder;
import com.ssafy.thorp.assistant.application.result.AnswerStatus;
import com.ssafy.thorp.assistant.application.result.AssistantAnswer;
import com.ssafy.thorp.assistant.application.result.Deadline;
import com.ssafy.thorp.assistant.application.result.Evidence;
import com.ssafy.thorp.assistant.application.result.EvidenceBundle;
import com.ssafy.thorp.assistant.application.result.EvidenceSource;
import com.ssafy.thorp.assistant.application.result.EvidenceSourceType;
import com.ssafy.thorp.assistant.application.result.PageContext;
import com.ssafy.thorp.assistant.application.result.PageKind;
import com.ssafy.thorp.assistant.application.result.RelatedArticle;
import com.ssafy.thorp.common.exception.BusinessException;
import com.ssafy.thorp.common.exception.ErrorCode;
import com.ssafy.thorp.result.application.service.PublicResultService;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * 질문 하나의 흐름(spec 015, 017). 순서가 곧 응답 코드의 우선순위다: 플래그(404) → 요청 검증(400) → 한도(429) → 페이지
 * 판정(400/404/410) → 근거 빌더(409) → 도구 루프 → 답변 검증·치환 → 접두 문장.
 *
 * <p>화면 종류는 식별자 규칙 하나로 갈린다. 결과 화면은 공개 토큰이 있어야 하고, 한도 몫은 토큰별이며, 모델 호출 전에 토큰을 판정한다. 진단 화면은 토큰이 있으면
 * 거절하고(017 FR-006), 한도 몫은 화면 종류별 IP다(017 FR-011).
 *
 * <p>플래그·토큰 판정이 모두 끝난 뒤에만 모델을 부른다(FR-005, SC-004). 질문·답변 원문은 저장하지 않고 로그에도 남기지 않는다(FR-014).
 */
@Service
public class AssistantQuestionService {
    static final String REGISTRY_PROVISIONAL_PREFIX = "이 설정 설명은 아직 확정 전의 임시 내용입니다. ";
    static final String SECTOR_SAMPLE_PREFIX = "이 섹터 목록은 예시 대응표입니다. ";
    static final int MAX_RELATED_ARTICLES = 3;
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
                    (kind.requiresPublicToken()
                                    ? "context 에는 " + kind + " 의 식별자 하나만 둡니다: "
                                    : "context 에는 kind 만 둡니다: ")
                            + command.extraContextKeys());
        }
        String question = command.question() == null ? "" : command.question().strip();
        if (question.isEmpty() || question.length() > settings.maxQuestionLength()) {
            throw new BusinessException(
                    ErrorCode.INVALID_INPUT,
                    "질문은 공백을 제외하고 1~" + settings.maxQuestionLength() + "자입니다");
        }
        String token = command.publicToken();
        if (kind.requiresPublicToken()) {
            if (token == null || token.isBlank()) {
                throw new BusinessException(ErrorCode.INVALID_INPUT, "publicToken 이 필요합니다");
            }
            limiter.admit(
                    command.clientIp(),
                    "token:" + token,
                    settings.perTokenPerMinute(),
                    "PER_TOKEN");
        } else {
            if (token != null) {
                throw new BusinessException(
                        ErrorCode.INVALID_INPUT, kind + " 에는 publicToken 을 보내지 않습니다");
            }
            limiter.admit(
                    command.clientIp(),
                    "page:" + kind + ":" + command.clientIp(),
                    settings.perPagePerIpPerMinute(),
                    "PER_PAGE");
        }
        PageContext context = new PageContext(kind, token);
        // 시간 예산은 여기서부터 센다 — 근거 팩 만들기도 그 안이다(FR-012).
        Deadline deadline = new Deadline(clock, settings.totalBudget());
        if (kind.requiresPublicToken()) {
            // 형식 → 존재 → 만료. 실패하면 모델을 부르지 않는다.
            publicResults.resolve(token);
        }
        EvidenceBundle bundle =
                builders.orderedStream()
                        .filter(builder -> builder.supports(kind))
                        .findFirst()
                        .map(builder -> builder.build(context))
                        .orElse(EvidenceBundle.empty());

        long started = System.nanoTime();
        ToolLoop.Outcome outcome = loop.run(model.getObject(), kind, question, bundle, deadline);
        AnswerValidator.Rendered rendered =
                validator.render(
                        outcome.draft(),
                        outcome.facts(),
                        AnswerValidator.Policy.of(kind),
                        reservedPrefixLength(bundle));
        String answer = prefixed(rendered, bundle) + rendered.answer();
        log.info(
                "assistant answered: kind={} draftStatus={} status={} rejectedBy={} rounds={} facts={} latencyMs={}",
                kind,
                outcome.draft() == null ? null : outcome.draft().status(),
                rendered.status(),
                rendered.rejectedBy(),
                outcome.rounds(),
                outcome.facts().size(),
                (System.nanoTime() - started) / 1_000_000);
        return new AssistantAnswer(
                rendered.status(), answer, rendered.evidence(), kind, relatedArticles(rendered));
    }

    /**
     * 검증이 끝난 뒤 서버가 붙이는 접두(검증 대상이 아니다). 결과 화면은 부분 결과 문장(015 FR-010), 진단 화면은 임시 등록표 → 예시 섹터 대응표
     * 순서다(017 FR-009b). 진단 화면 접두는 그 출처의 근거를 실제로 쓴 {@code ANSWERED} 답에만 붙는다.
     */
    private static String prefixed(AnswerValidator.Rendered rendered, EvidenceBundle bundle) {
        StringBuilder prefix = new StringBuilder();
        if (bundle.partialUntil() != null) {
            prefix.append("이 결과는 ").append(bundle.partialUntil()).append("까지의 부분 결과입니다. ");
        }
        if (rendered.status() == AnswerStatus.ANSWERED) {
            if (bundle.registryProvisional() && uses(rendered, EvidenceSourceType.REGISTRY)) {
                prefix.append(REGISTRY_PROVISIONAL_PREFIX);
            }
            if (bundle.sectorSample() && uses(rendered, EvidenceSourceType.SECTOR)) {
                prefix.append(SECTOR_SAMPLE_PREFIX);
            }
        }
        return prefix.toString();
    }

    /**
     * 진단 화면 접두가 붙을 수 있는 최대 길이. 검증기가 답 길이 상한에서 미리 빼서, 접두를 붙인 최종 답도 상한을 넘지 않게 한다(계약 §2.4). 결과 화면의 부분
     * 결과 접두는 015 계약대로 상한 밖이라 넣지 않는다.
     */
    private static int reservedPrefixLength(EvidenceBundle bundle) {
        return (bundle.registryProvisional() ? REGISTRY_PROVISIONAL_PREFIX.length() : 0)
                + (bundle.sectorSample() ? SECTOR_SAMPLE_PREFIX.length() : 0);
    }

    private static boolean uses(AnswerValidator.Rendered rendered, EvidenceSourceType type) {
        return rendered.evidence().stream()
                .map(Evidence::source)
                .anyMatch(source -> source != null && source.type() == type);
    }

    /** 쓰인 근거의 배우기 글. 중복 없이 처음 쓰인 순서로 최대 3개(017 FR-010). */
    private static List<RelatedArticle> relatedArticles(AnswerValidator.Rendered rendered) {
        Map<String, RelatedArticle> articles = new LinkedHashMap<>();
        for (Evidence fact : rendered.evidence()) {
            EvidenceSource source = fact.source();
            if (source == null || source.articleId() == null) {
                continue;
            }
            articles.putIfAbsent(
                    source.articleId(),
                    new RelatedArticle(source.articleId(), source.articleTitle()));
            if (articles.size() == MAX_RELATED_ARTICLES) {
                break;
            }
        }
        return List.copyOf(articles.values());
    }

    private static PageKind parseKind(String kind) {
        try {
            return PageKind.valueOf(kind == null ? "" : kind);
        } catch (IllegalArgumentException exc) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "알 수 없는 화면 종류입니다");
        }
    }
}
