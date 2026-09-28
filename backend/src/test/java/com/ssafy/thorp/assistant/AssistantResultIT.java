package com.ssafy.thorp.assistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ssafy.thorp.assistant.application.port.AssistantModelPort;
import com.ssafy.thorp.assistant.application.result.AnswerStatus;
import com.ssafy.thorp.assistant.application.result.Conversation;
import com.ssafy.thorp.assistant.application.result.Evidence;
import com.ssafy.thorp.assistant.application.result.EvidenceKind;
import com.ssafy.thorp.assistant.application.result.ModelDraft;
import com.ssafy.thorp.assistant.application.result.ModelDraft.AnswerPart;
import com.ssafy.thorp.assistant.application.result.ToolCall;
import com.ssafy.thorp.assistant.application.result.ToolRequests;
import com.ssafy.thorp.assistant.application.result.ToolSpec;
import com.ssafy.thorp.backtest.application.command.SaveBacktestResultCommand;
import com.ssafy.thorp.backtest.application.repository.BacktestRequestRepository;
import com.ssafy.thorp.backtest.application.repository.BacktestResultRepository;
import com.ssafy.thorp.backtest.application.repository.BacktestRunRepository;
import com.ssafy.thorp.backtest.domain.model.BacktestRequest;
import com.ssafy.thorp.backtest.domain.model.BacktestRun;
import com.ssafy.thorp.backtest.domain.model.ExecutionType;
import com.ssafy.thorp.backtest.domain.model.PositionSizingMode;
import com.ssafy.thorp.backtest.domain.model.PublicResultToken;
import com.ssafy.thorp.backtest.domain.model.ResultSource;
import com.ssafy.thorp.backtest.domain.model.RunPlan;
import com.ssafy.thorp.backtest.domain.vo.BacktestPeriod;
import com.ssafy.thorp.backtest.domain.vo.InstrumentId;
import com.ssafy.thorp.backtest.infrastructure.messaging.MessageFixtures;
import com.ssafy.thorp.support.PortMockedIntegrationTestSupport;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 완료 결과 하나로 끝까지 흐른다(T035): 공개 토큰 → 근거 팩 → 가짜 모델의 도구 요청 → 도구 실행 → 자리표시자 답 → 치환. 모델만 가짜다. 응답은 {@code
 * examples/answer-answered.json}과 같은 모양이다. {@code ResultApiIT}는 건드리지 않는다.
 */
@MockitoBean(types = AssistantModelPort.class)
@TestPropertySource(properties = "thorp.assistant.enabled-scopes=RESULT")
class AssistantResultIT extends PortMockedIntegrationTestSupport {
    static final Path EXAMPLES = Path.of("..", "docs", "specs", "assistant-api", "examples");

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private BacktestRunRepository runRepository;
    @Autowired private BacktestRequestRepository requestRepository;
    @Autowired private BacktestResultRepository resultRepository;
    @Autowired private TransactionTemplate transactionTemplate;
    @Autowired private AssistantModelPort model;

    private BacktestRun newRun() {
        Map<String, Object> versions = new LinkedHashMap<>();
        versions.put("mapping", "SECTOR-ETF-2026-09");
        versions.put("rules", Map.of("version", "1", "provisional", false));
        versions.put("costProfile", Map.of("version", "PROVISIONAL-DEMO-1", "provisional", true));
        versions.put("data", "FIXTURE-three_etf_24m");
        versions.put("engine", "nautilus_trader 1.219.0 / worker 2.3.0 / uv.lock 8e8a6be0a6a8");
        versions.put("evaluationSplit", "SPLIT-1");
        return runRepository.save(
                BacktestRun.create(
                                "EQUAL_WEIGHT",
                                "1.0.0",
                                Map.of("rebalancePeriod", "MONTHLY"),
                                List.of(
                                        new InstrumentId("005930.XKRX"),
                                        new InstrumentId("000660.XKRX")),
                                new BacktestPeriod(
                                        LocalDate.of(2025, 1, 1), LocalDate.of(2025, 6, 30)),
                                new BigDecimal("10000000"),
                                PositionSizingMode.STRATEGY_DEFINED,
                                new BigDecimal("0.50"),
                                new InstrumentId("069500.XKRX"),
                                42)
                        .withPlan(
                                new RunPlan(
                                        null,
                                        null,
                                        null,
                                        null,
                                        LocalDate.of(2025, 1, 1),
                                        LocalDate.of(2025, 6, 30),
                                        0,
                                        null,
                                        versions,
                                        null)));
    }

    private BacktestRun completedRun() {
        return transactionTemplate.execute(
                status -> {
                    BacktestRun run = newRun();
                    var event = MessageFixtures.finishedCompleted();
                    resultRepository.save(
                            new SaveBacktestResultCommand(
                                    run.getId(),
                                    event.executionId(),
                                    event.eventId(),
                                    event.payload().engineOutput(),
                                    null));
                    return runRepository.update(run.markCompleted());
                });
    }

    private String anonymousTokenFor(BacktestRun run) {
        PublicResultToken token = PublicResultToken.generate();
        requestRepository.save(
                BacktestRequest.create(
                        run.getId(),
                        null,
                        ExecutionType.SINGLE,
                        ResultSource.NEW,
                        null,
                        token,
                        Instant.now().plus(1, ChronoUnit.DAYS)));
        return token.value();
    }

    private static String body(String question, String token) {
        return "{\"question\":\""
                + question
                + "\",\"context\":{\"kind\":\"RESULT\",\"publicToken\":\""
                + token
                + "\"}}";
    }

    @Test
    @DisplayName("완료 결과에 날짜를 물으면 근거 팩 → 도구 → 자리표시자 답이 예시 모양의 200 으로 끝난다")
    void completedResultAnswersThroughTools() throws Exception {
        String token = anonymousTokenFor(completedRun());
        List<List<Evidence>> seenFacts = new ArrayList<>();
        List<List<ToolSpec>> seenTools = new ArrayList<>();
        AtomicInteger calls = new AtomicInteger();
        when(model.step(any(Conversation.class), any(), any(), any()))
                .thenAnswer(
                        invocation -> {
                            List<Evidence> facts = invocation.getArgument(1);
                            seenFacts.add(facts);
                            seenTools.add(invocation.getArgument(2));
                            if (calls.incrementAndGet() == 1) {
                                return new ToolRequests(
                                        List.of(
                                                new ToolCall(
                                                        "call_1",
                                                        "getEquityOn",
                                                        Map.of("date", "2025-03-19"))));
                            }
                            Evidence equity =
                                    facts.stream()
                                            .filter(f -> f.label().equals("자산가치"))
                                            .findFirst()
                                            .orElseThrow();
                            Evidence net =
                                    facts.stream()
                                            .filter(f -> f.label().equals("비용 차감 후 전체 수익률"))
                                            .findFirst()
                                            .orElseThrow();
                            return new ModelDraft(
                                    AnswerStatus.ANSWERED,
                                    List.of(
                                            new AnswerPart(
                                                    "그날 자산가치는 {{"
                                                            + equity.id()
                                                            + "}}였고, 전체 기간 비용 차감 후 수익률은 {{"
                                                            + net.id()
                                                            + "}}입니다.",
                                                    List.of(equity.id(), net.id()))));
                        });

        String response =
                mockMvc.perform(
                                post("/assistant/questions")
                                        .with(csrf())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(body("2025년 3월 19일 자산은 얼마였어?", token)))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();

        JsonNode actual = objectMapper.readTree(response);
        JsonNode example =
                objectMapper.readTree(Files.readString(EXAMPLES.resolve("answer-answered.json")));
        assertThat(actual.get("success").asBoolean()).isTrue();
        JsonNode data = actual.get("data");
        assertThat(data.fieldNames())
                .toIterable()
                .containsExactlyInAnyOrderElementsOf(() -> example.get("data").fieldNames());
        assertThat(data.get("status").asText()).isEqualTo("ANSWERED");
        assertThat(data.get("page").get("kind").asText()).isEqualTo("RESULT");
        assertThat(data.get("evidence")).hasSize(2);
        JsonNode equity = data.get("evidence").get(0);
        assertThat(equity.get("label").asText()).isEqualTo("자산가치");
        assertThat(equity.get("asOf").asText()).isEqualTo("2025-03-19");
        assertThat(equity.get("value").asText()).endsWith("원");
        // SC-003: 근거 값은 결과 API 가 돌려주는 값을 표기한 것과 정확히 같다 — 팩이 어디서 왔는지가 아니라 값 자체를 대조한다.
        JsonNode timeseries =
                objectMapper
                        .readTree(
                                mockMvc.perform(get("/results/" + token + "/timeseries"))
                                        .andExpect(status().isOk())
                                        .andReturn()
                                        .getResponse()
                                        .getContentAsString())
                        .get("data")
                        .get("points");
        String storedEquity = null;
        for (JsonNode point : timeseries) {
            if (point.get("date").asText().equals("2025-03-19")) {
                storedEquity = point.get("equity").asText();
            }
        }
        assertThat(storedEquity).isNotNull();
        assertThat(equity.get("value").asText()).isEqualTo(EvidenceKind.MONEY.format(storedEquity));
        JsonNode summary =
                objectMapper
                        .readTree(
                                mockMvc.perform(get("/results/" + token))
                                        .andExpect(status().isOk())
                                        .andReturn()
                                        .getResponse()
                                        .getContentAsString())
                        .get("data")
                        .get("summary");
        assertThat(data.get("evidence").get(1).get("value").asText())
                .isEqualTo(EvidenceKind.RATIO.format(summary.get("netTotalReturn").asText()));
        assertThat(data.get("answer").asText())
                .contains(equity.get("value").asText())
                .contains("16.67%");
        assertThat(response).doesNotContain(token, "BT-", "backtestId");

        assertThat(seenFacts).hasSize(2);
        assertThat(seenTools.get(0))
                .extracting(ToolSpec::name)
                .contains("getEquityOn", "getDeepestDrawdownWindow");
        assertThat(seenFacts.get(0).toString()).doesNotContain(token, "BT-");
        assertThat(seenFacts.get(0))
                .extracting(Evidence::label)
                .contains("비용 차감 후 전체 수익률", "거래비용 합계", "전략");
        assertThat(seenFacts.get(1).size()).isEqualTo(seenFacts.get(0).size() + 3);
    }

    @Test
    @DisplayName("계산 중인 결과는 409 이고 모델을 부르지 않는다")
    void notReadyResultIsConflictWithoutModel() throws Exception {
        String token = anonymousTokenFor(transactionTemplate.execute(status -> newRun()));

        mockMvc.perform(
                        post("/assistant/questions")
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body("수익률은?", token)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("RESULT_NOT_READY"));

        verifyNoInteractions(model);
    }

    @Test
    @DisplayName("capabilities 는 켜져 있고 결과 화면 경로도 그대로다")
    void capabilitiesAndResultRoutesCoexist() throws Exception {
        String token = anonymousTokenFor(completedRun());

        mockMvc.perform(get("/assistant/capabilities"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.enabled").value(true))
                .andExpect(jsonPath("$.data.scopes[0]").value("RESULT"));
        mockMvc.perform(get("/results/" + token)).andExpect(status().isOk());
        verify(model, org.mockito.Mockito.never()).step(any(), any(), any(), any());
    }
}
