package com.ssafy.thorp.assistant;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ssafy.thorp.assistant.application.port.AssistantSettings;
import com.ssafy.thorp.assistant.application.port.AssistantTool;
import com.ssafy.thorp.assistant.application.result.AnswerStatus;
import com.ssafy.thorp.assistant.application.result.Deadline;
import com.ssafy.thorp.assistant.application.result.Evidence;
import com.ssafy.thorp.assistant.application.result.EvidenceBundle;
import com.ssafy.thorp.assistant.application.result.EvidenceKind;
import com.ssafy.thorp.assistant.application.result.PageKind;
import com.ssafy.thorp.assistant.application.result.ToolSpec;
import com.ssafy.thorp.assistant.application.service.AnswerValidator;
import com.ssafy.thorp.assistant.application.service.ToolLoop;
import com.ssafy.thorp.assistant.infrastructure.client.AssistantHttpClient;
import com.ssafy.thorp.assistant.infrastructure.client.OpenAiChatCompletionsClient;
import com.ssafy.thorp.assistant.infrastructure.config.AssistantModelProperties;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * 진짜 GMS 게이트웨이에 어댑터·루프·검증기를 한 번 통과시킨다. {@code ASSISTANT_LIVE_TEST=true}와 {@code THORP_ASSISTANT_*}
 * 환경 변수가 있을 때만 돈다 — CI 에서는 skip 이다({@code MarketLiveApiTest}와 같은 방식). 답 원문은 출력하되 키는 출력하지 않는다.
 */
@EnabledIfEnvironmentVariable(named = "ASSISTANT_LIVE_TEST", matches = "true")
class AssistantLiveTest {

    @Test
    @DisplayName("날짜 질문에 도구를 요청하고, 도구 근거로 자리표시자 답을 만들며, 검증기를 통과한다")
    void dateQuestionGoesThroughToolAndValidator() {
        AssistantModelProperties properties = new AssistantModelProperties();
        properties.setApiKey(System.getenv("THORP_ASSISTANT_API_KEY"));
        properties.setBaseUrl(System.getenv("THORP_ASSISTANT_BASE_URL"));
        properties.setModel(System.getenv("THORP_ASSISTANT_MODEL"));
        ObjectMapper mapper = new ObjectMapper();
        OpenAiChatCompletionsClient model =
                new OpenAiChatCompletionsClient(
                        new AssistantHttpClient(properties, mapper), properties, mapper);
        AssistantSettings settings =
                new AssistantSettings(
                        Set.of(PageKind.RESULT),
                        8,
                        5,
                        1000,
                        300,
                        Duration.ofMinutes(2),
                        Duration.ofSeconds(60),
                        Duration.ofSeconds(30),
                        2,
                        60,
                        30,
                        600,
                        5);
        AtomicInteger toolRuns = new AtomicInteger();
        AssistantTool equityOn =
                new AssistantTool() {
                    @Override
                    public ToolSpec spec() {
                        return ToolSpec.dates(
                                "getEquityOn", "특정 날짜의 자산가치·누적 수익률·하락 폭", List.of("date"));
                    }

                    @Override
                    public List<Evidence> run(Map<String, String> arguments) {
                        toolRuns.incrementAndGet();
                        LocalDate date = LocalDate.parse(arguments.get("date"));
                        return List.of(
                                new Evidence(
                                        null,
                                        "자산가치",
                                        "10523000",
                                        date,
                                        "summary",
                                        EvidenceKind.MONEY),
                                new Evidence(
                                        null,
                                        "누적 수익률",
                                        "0.0523",
                                        date,
                                        "summary",
                                        EvidenceKind.RATIO));
                    }
                };
        EvidenceBundle bundle =
                new EvidenceBundle(
                        List.of(
                                new Evidence(
                                        null,
                                        "비용 차감 후 전체 수익률",
                                        "0.166652",
                                        LocalDate.of(2024, 12, 31),
                                        "summary",
                                        EvidenceKind.RATIO),
                                new Evidence(
                                        null,
                                        "평가 시작일",
                                        "2024-01-02",
                                        null,
                                        "settings",
                                        EvidenceKind.DATE),
                                new Evidence(
                                        null,
                                        "평가 종료일",
                                        "2024-12-31",
                                        null,
                                        "settings",
                                        EvidenceKind.DATE)),
                        List.of(equityOn),
                        null);

        ToolLoop.Outcome outcome =
                new ToolLoop(settings)
                        .run(
                                model,
                                "2024년 3월 4일에 자산은 얼마였어?",
                                bundle,
                                new Deadline(Clock.systemUTC(), settings.totalBudget()));
        AnswerValidator.Rendered rendered =
                new AnswerValidator(settings).render(outcome.draft(), outcome.facts());

        System.out.println("[LIVE] rounds=" + outcome.rounds() + " toolRuns=" + toolRuns.get());
        System.out.println("[LIVE] draft=" + outcome.draft());
        System.out.println("[LIVE] status=" + rendered.status() + " answer=" + rendered.answer());
        System.out.println("[LIVE] evidence=" + rendered.evidence());

        assertThat(toolRuns.get()).isGreaterThanOrEqualTo(1);
        assertThat(rendered.status()).isEqualTo(AnswerStatus.ANSWERED);
        assertThat(rendered.evidence()).isNotEmpty();
    }
}
