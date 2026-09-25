package com.ssafy.thorp.assistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ssafy.thorp.assistant.application.exception.ToolUnavailableException;
import com.ssafy.thorp.assistant.application.port.AssistantModelPort;
import com.ssafy.thorp.assistant.application.port.AssistantTool;
import com.ssafy.thorp.assistant.application.port.EvidenceBuilder;
import com.ssafy.thorp.assistant.application.result.AnswerStatus;
import com.ssafy.thorp.assistant.application.result.Conversation;
import com.ssafy.thorp.assistant.application.result.Evidence;
import com.ssafy.thorp.assistant.application.result.EvidenceBundle;
import com.ssafy.thorp.assistant.application.result.EvidenceKind;
import com.ssafy.thorp.assistant.application.result.ModelDraft;
import com.ssafy.thorp.assistant.application.result.ModelDraft.AnswerPart;
import com.ssafy.thorp.assistant.application.result.ModelTurn;
import com.ssafy.thorp.assistant.application.result.PageContext;
import com.ssafy.thorp.assistant.application.result.PageKind;
import com.ssafy.thorp.assistant.application.result.ToolCall;
import com.ssafy.thorp.assistant.application.result.ToolRequests;
import com.ssafy.thorp.assistant.application.result.ToolSpec;
import com.ssafy.thorp.assistant.application.service.AnswerValidator;
import com.ssafy.thorp.assistant.application.service.AssistantCapabilities;
import com.ssafy.thorp.assistant.application.service.AssistantQuestionService;
import com.ssafy.thorp.assistant.application.service.AssistantRateLimiter;
import com.ssafy.thorp.assistant.application.service.ToolLoop;
import com.ssafy.thorp.assistant.infrastructure.config.AssistantConfig;
import com.ssafy.thorp.assistant.presentation.controller.AssistantController;
import com.ssafy.thorp.common.config.SecurityConfig;
import com.ssafy.thorp.common.exception.BusinessException;
import com.ssafy.thorp.common.exception.ErrorCode;
import com.ssafy.thorp.result.application.service.PublicResultService;
import java.io.IOException;
import java.io.InputStream;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * 품질 관문의 자동 검사(T051). 표본 {@code assistant/quality-cases.json} 30개를 가짜 모델·고정 fixture 로 끝까지 흘려, 모델이
 * 이상적으로 답했을 때 서버가 지켜야 할 것을 고정한다: 근거 ID 가 팩에 있다, 답의 숫자가 전부 fixture 값이다, 모델 입력 어디에도 토큰·{@code
 * backtestId}가 없다, 만료 토큰은 모델을 부르지 않는다, 모르는 도구·기간 밖 인자는 근거를 내지 않는다, 503 이 계약대로 매핑된다.
 *
 * <p>실제 모델의 답 품질(SC-001~SC-003)은 여기서 재지 않는다 — {@code docs/specs/assistant-api/evaluation.md}가 같은
 * 표본으로 잰다. 429 는 {@code AssistantControllerTest}가 세 한도를 따로 고정한다. Docker 없이 돈다.
 */
@WebMvcTest(AssistantController.class)
@Import({
    SecurityConfig.class,
    AssistantConfig.class,
    AssistantQuestionService.class,
    AssistantCapabilities.class,
    AssistantRateLimiter.class,
    AnswerValidator.class,
    ToolLoop.class,
    AssistantQualityTest.Fakes.class
})
@TestPropertySource(
        properties = {
            "thorp.assistant.enabled-scopes=RESULT",
            "thorp.assistant.limits.per-ip-per-minute=1000",
            "thorp.assistant.limits.per-token-per-minute=1000",
            "thorp.assistant.limits.per-day=10000"
        })
class AssistantQualityTest {
    static final String TOKEN = "0123456789abcdef0123456789abcdef";
    static final String PARTIAL_TOKEN = "fedcba9876543210fedcba9876543210";
    static final String BACKTEST_ID = "BT-20260907-000001";
    static final LocalDate START = LocalDate.of(2023, 7, 3);
    static final LocalDate END = LocalDate.of(2024, 12, 31);
    static final LocalDate PARTIAL_UNTIL = LocalDate.of(2024, 6, 28);
    static final Pattern NUMBER = Pattern.compile("-?[0-9][0-9,]*(?:\\.[0-9]+)?%?원?");

    /** 표본 한 행. {@code quality-cases.json}의 필드와 1:1 이다. */
    record Case(
            String id,
            String group,
            String question,
            List<String> expectedTools,
            List<String> requiredEvidenceKinds,
            List<String> forbiddenClaims,
            String expectedStatus,
            boolean partial) {
        @Override
        public String toString() {
            return id + " " + question;
        }
    }

    static List<Case> cases() throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        try (InputStream in =
                AssistantQualityTest.class
                        .getClassLoader()
                        .getResourceAsStream("assistant/quality-cases.json")) {
            return mapper.readValue(
                    in, mapper.getTypeFactory().constructCollectionType(List.class, Case.class));
        }
    }

    /** 시도를 세는 가짜 도구. 기간 밖은 근거를 내지 않고 거절하며, 근거를 낸 순서는 공유 목록에 남긴다. */
    static final class CountingTool implements AssistantTool {
        final ToolSpec spec;
        final Function<Map<String, String>, List<Evidence>> produce;
        final List<String> executionOrder;
        final List<Map<String, String>> attempts = new ArrayList<>();

        CountingTool(
                ToolSpec spec,
                Function<Map<String, String>, List<Evidence>> produce,
                List<String> executionOrder) {
            this.spec = spec;
            this.produce = produce;
            this.executionOrder = executionOrder;
        }

        @Override
        public ToolSpec spec() {
            return spec;
        }

        @Override
        public List<Evidence> run(Map<String, String> arguments) {
            attempts.add(arguments);
            for (String value : arguments.values()) {
                LocalDate date = LocalDate.parse(value);
                if (date.isBefore(START) || date.isAfter(END)) {
                    throw new ToolUnavailableException("결과 기간 밖의 날짜입니다");
                }
            }
            executionOrder.add(spec.name());
            return produce.apply(arguments);
        }
    }

    /** 가짜 모델·근거 빌더·시계. 테스트가 {@link #script}로 모델을 바꾸고, 모델이 받은 입력을 전부 남긴다. */
    @TestConfiguration
    static class Fakes {
        volatile Function<Turn, ModelTurn> script =
                turn -> new ModelDraft(AnswerStatus.GREETING, List.of());
        final List<Turn> turns = new ArrayList<>();
        final List<CountingTool> tools = new ArrayList<>();

        /** 근거를 실제로 낸 도구 이름, 실행 순서대로. */
        final List<String> executed = new ArrayList<>();

        final MutableClock clock = new MutableClock(Instant.parse("2026-09-25T03:00:00Z"));

        record Turn(Conversation conversation, List<Evidence> facts, List<ToolSpec> tools) {}

        @Bean
        Clock clock() {
            return clock;
        }

        @Bean
        AssistantModelPort fakeModel() {
            return (conversation, facts, specs, timeout) -> {
                Turn turn = new Turn(conversation, facts, specs);
                turns.add(turn);
                return script.apply(turn);
            };
        }

        @Bean
        EvidenceBuilder fakeBuilder() {
            return new EvidenceBuilder() {
                @Override
                public boolean supports(PageKind kind) {
                    return kind == PageKind.RESULT;
                }

                @Override
                public EvidenceBundle build(PageContext context) {
                    tools.clear();
                    executed.clear();
                    tools.addAll(fixtureTools(executed));
                    boolean partial = PARTIAL_TOKEN.equals(context.publicToken());
                    return new EvidenceBundle(
                            fixturePack(), List.copyOf(tools), partial ? PARTIAL_UNTIL : null);
                }
            };
        }
    }

    // ---- 고정 fixture: 계약 §2.1 포함 표의 축약본. 값은 원문(치환 전) 문자열이다. 라벨에 숫자를 두지 않는다 — 검증기가
    // 자리표시자 밖 숫자를 거절하므로, 라벨을 문장에 옮겨 적는 이상적인 모델도 그 규칙 안에 있어야 한다.

    static Evidence fact(
            String label, String value, LocalDate asOf, String tab, EvidenceKind kind) {
        return new Evidence(null, label, value, asOf, tab, kind);
    }

    static List<Evidence> fixturePack() {
        return List.of(
                fact("시작 자산", "11110000.00", null, "summary", EvidenceKind.MONEY),
                fact("종료 자산", "12961538.71", null, "summary", EvidenceKind.MONEY),
                fact("전체 수익률", "0.168000", null, "summary", EvidenceKind.RATIO),
                fact("비용 차감 후 전체 수익률", "0.166652", END, "summary", EvidenceKind.RATIO),
                fact("연복리 수익률", "0.110000", null, "summary", EvidenceKind.RATIO),
                fact("가장 크게 줄었던 폭", "-0.041757", null, "summary", EvidenceKind.RATIO),
                fact("변동성", "0.150000", null, "summary", EvidenceKind.RATIO),
                fact("거래 횟수", "24", null, "summary", EvidenceKind.INTEGER),
                fact("거래비용 합계", "17078.49", null, "settings", EvidenceKind.MONEY),
                fact("수수료", "12000", null, "settings", EvidenceKind.MONEY),
                fact("스프레드", "0.00", null, "settings", EvidenceKind.MONEY),
                fact("슬리피지", "5078.49", null, "settings", EvidenceKind.MONEY),
                fact("세금", "0.00", null, "settings", EvidenceKind.MONEY),
                fact(
                        "세금 규칙",
                        "국내 상장 ETF 매도에는 증권거래세가 없어 0으로 둔다",
                        null,
                        "settings",
                        EvidenceKind.TEXT),
                fact("요청 시작일", "2023-07-01", null, "settings", EvidenceKind.DATE),
                fact("요청 종료일", "2024-12-31", null, "settings", EvidenceKind.DATE),
                fact("시작 금액", "11110000.00", null, "settings", EvidenceKind.MONEY),
                fact("전략", "똑같이 나누기", null, "settings", EvidenceKind.TEXT),
                fact("리밸런싱 주기", "월말", null, "settings", EvidenceKind.TEXT),
                fact("반도체 대표 ETF", "091160.XKRX", null, "settings", EvidenceKind.TEXT),
                fact("반도체 목표 비중", "0.4000", null, "settings", EvidenceKind.RATIO),
                fact("반도체 실제 비중", "0.4123", END, "settings", EvidenceKind.RATIO));
    }

    static List<CountingTool> fixtureTools(List<String> order) {
        return List.of(
                new CountingTool(
                        new ToolSpec("getEquityOn", "특정 날짜의 시계열 점", List.of("date")),
                        args -> {
                            LocalDate date = LocalDate.parse(args.get("date"));
                            return List.of(
                                    fact("자산가치", "11850000.50", date, "trades", EvidenceKind.MONEY),
                                    fact("누적 수익률", "0.066600", date, "trades", EvidenceKind.RATIO),
                                    fact("하락 폭", "-0.012300", date, "loss", EvidenceKind.RATIO));
                        },
                        order),
                new CountingTool(
                        new ToolSpec("getEquityRange", "구간의 첫·끝·최고·최저 점", List.of("from", "to")),
                        args -> {
                            LocalDate from = LocalDate.parse(args.get("from"));
                            LocalDate to = LocalDate.parse(args.get("to"));
                            return List.of(
                                    fact(
                                            "구간 첫 자산가치",
                                            "11700000.00",
                                            from,
                                            "trades",
                                            EvidenceKind.MONEY),
                                    fact(
                                            "구간 끝 자산가치",
                                            "11900000.00",
                                            to,
                                            "trades",
                                            EvidenceKind.MONEY),
                                    fact(
                                            "구간 최고 자산가치",
                                            "12050000.00",
                                            to,
                                            "trades",
                                            EvidenceKind.MONEY),
                                    fact(
                                            "구간 최고 날짜",
                                            to.toString(),
                                            to,
                                            "trades",
                                            EvidenceKind.DATE),
                                    fact(
                                            "구간 끝 누적 수익률",
                                            "0.071100",
                                            to,
                                            "trades",
                                            EvidenceKind.RATIO));
                        },
                        order),
                new CountingTool(
                        new ToolSpec("getTradesBetween", "구간의 거래 첫 페이지", List.of("from", "to")),
                        args -> {
                            LocalDate from = LocalDate.parse(args.get("from"));
                            return List.of(
                                    fact(
                                            "첫째 거래",
                                            "091160.XKRX 매수",
                                            from,
                                            "trades",
                                            EvidenceKind.TEXT),
                                    fact(
                                            "둘째 거래",
                                            "305720.XKRX 매도",
                                            from,
                                            "trades",
                                            EvidenceKind.TEXT));
                        },
                        order),
                new CountingTool(
                        new ToolSpec("getPositionsOn", "특정 날짜의 보유", List.of("date")),
                        args -> {
                            LocalDate date = LocalDate.parse(args.get("date"));
                            return List.of(
                                    fact("반도체 보유 비중", "0.3980", date, "trades", EvidenceKind.RATIO),
                                    fact(
                                            "이차전지 보유 비중",
                                            "0.6020",
                                            date,
                                            "trades",
                                            EvidenceKind.RATIO));
                        },
                        order),
                new CountingTool(
                        new ToolSpec("getDeepestDrawdownWindow", "하락 폭이 가장 낮은 구간", List.of()),
                        args ->
                                List.of(
                                        fact(
                                                "하락 시작(고점) 날짜",
                                                "2024-03-04",
                                                LocalDate.of(2024, 3, 4),
                                                "loss",
                                                EvidenceKind.DATE),
                                        fact(
                                                "가장 크게 줄어든 날짜",
                                                "2024-04-19",
                                                LocalDate.of(2024, 4, 19),
                                                "loss",
                                                EvidenceKind.DATE),
                                        fact(
                                                "저점 하락 폭",
                                                "-0.041757",
                                                LocalDate.of(2024, 4, 19),
                                                "loss",
                                                EvidenceKind.RATIO)),
                        order));
    }

    // ---- 이상적인 모델: 표본이 기대하는 도구를 요청하고, 요구 종류의 근거를 자리표시자로만 쓴다.

    static Function<Fakes.Turn, ModelTurn> idealModel(Case c) {
        return turn -> {
            AnswerStatus expected = AnswerStatus.valueOf(c.expectedStatus());
            boolean firstTurn = turn.conversation().exchanges().isEmpty();
            if (!c.expectedTools().isEmpty() && firstTurn) {
                List<ToolCall> calls = new ArrayList<>();
                int n = 0;
                for (String name : c.expectedTools()) {
                    ToolSpec spec =
                            turn.tools().stream()
                                    .filter(s -> s.name().equals(name))
                                    .findFirst()
                                    .orElseThrow();
                    Map<String, String> args = new LinkedHashMap<>();
                    for (String arg : spec.dateArguments()) {
                        args.put(arg, dateArgument(c, arg));
                    }
                    calls.add(new ToolCall("call-" + (++n), name, args));
                }
                return new ToolRequests(calls);
            }
            if (expected != AnswerStatus.ANSWERED) {
                return new ModelDraft(expected, List.of());
            }
            List<AnswerPart> parts = new ArrayList<>();
            for (String kind : c.requiredEvidenceKinds()) {
                Evidence pick = lastOfKind(turn.facts(), EvidenceKind.valueOf(kind));
                assertThat(pick)
                        .as("fixture 에 %s 종류 근거가 있어야 표본 %s 를 돌릴 수 있다", kind, c.id())
                        .isNotNull();
                parts.add(
                        new AnswerPart(
                                pick.label() + "은(는) {{" + pick.id() + "}}입니다.",
                                List.of(pick.id())));
            }
            return new ModelDraft(AnswerStatus.ANSWERED, parts);
        };
    }

    /** 질문에 적힌 날짜를 그대로 쓴다(기간 밖 표본은 그 값이 거절돼야 한다). 없으면 기간 안 고정값이다. */
    static String dateArgument(Case c, String arg) {
        Matcher m = Pattern.compile("\\d{4}-\\d{2}-\\d{2}").matcher(c.question());
        if (m.find()) {
            return m.group();
        }
        return switch (arg) {
            case "from" -> "2024-03-01";
            case "to" -> "2024-03-29";
            default -> "2024-03-04";
        };
    }

    /** 같은 종류가 여럿이면 뒤엣것(도구 결과)을 고른다 — 도구 표본이 도구 근거를 실제로 쓰게 하기 위해서다. */
    static Evidence lastOfKind(List<Evidence> facts, EvidenceKind kind) {
        Evidence found = null;
        for (Evidence fact : facts) {
            if (fact.kind() == kind) {
                found = fact;
            }
        }
        return found;
    }

    // ---- MockMvc

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private Fakes fakes;
    @MockitoBean private PublicResultService publicResults;

    @BeforeEach
    void reset() {
        fakes.clock.advance(Duration.ofDays(2));
        fakes.turns.clear();
    }

    ResultActions ask(String question, String token) throws Exception {
        String body =
                objectMapper.writeValueAsString(
                        Map.of(
                                "question",
                                question,
                                "context",
                                Map.of("kind", "RESULT", "publicToken", token)));
        return mockMvc.perform(
                post("/assistant/questions")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body));
    }

    JsonNode json(ResultActions actions) throws Exception {
        return objectMapper.readTree(actions.andReturn().getResponse().getContentAsString());
    }

    static Stream<Case> sample() throws IOException {
        return cases().stream();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("sample")
    @DisplayName("표본 30개: 상태·근거 ID·치환 숫자·금지 표현·도구 실행·비노출이 계약대로다")
    void sampleHoldsContract(Case c) throws Exception {
        fakes.script = idealModel(c);
        String token = c.partial() ? PARTIAL_TOKEN : TOKEN;

        JsonNode data = json(ask(c.question(), token).andExpect(status().isOk())).get("data");

        assertThat(data.get("status").asText()).as(c.id()).isEqualTo(c.expectedStatus());
        String answer = data.get("answer").asText();
        List<JsonNode> evidence = new ArrayList<>();
        data.get("evidence").forEach(evidence::add);

        // 근거 ID 는 모델이 본 팩(초기 + 도구 결과)에 있는 것뿐이다.
        List<String> seenIds =
                fakes.turns.stream()
                        .flatMap(t -> t.facts().stream())
                        .map(Evidence::id)
                        .filter(id -> id != null)
                        .toList();
        for (JsonNode fact : evidence) {
            assertThat(seenIds).as(c.id()).contains(fact.get("id").asText());
        }

        // 답의 숫자·날짜는 전부 근거의 표기 값 안에 있다(모델이 적은 숫자가 없다).
        List<String> formatted = evidence.stream().map(f -> f.get("value").asText()).toList();
        String body = c.partial() ? answer.substring(answer.indexOf('.') + 1) : answer;
        Matcher m = NUMBER.matcher(body);
        while (m.find()) {
            String number = m.group();
            assertThat(formatted)
                    .as("%s 답의 값 %s 은 근거 값이어야 한다", c.id(), number)
                    .anyMatch(value -> value.contains(number));
        }

        if (c.expectedStatus().equals("ANSWERED")) {
            // 금지 표현은 모델이 쓴 답에만 본다. 고정 문구는 서버 상수라 "추천" 같은 낱말이 안내에 들어 있다.
            for (String claim : c.forbiddenClaims()) {
                assertThat(answer).as(c.id()).doesNotContain(claim);
            }
            assertThat(evidence).as(c.id()).isNotEmpty();
            assertThat(answer).doesNotContain("{{");
        } else {
            assertThat(evidence).as(c.id()).isEmpty();
        }
        if (c.partial()) {
            assertThat(answer).startsWith("이 결과는 " + PARTIAL_UNTIL + "까지의 부분 결과입니다.");
        }

        // 도구는 표본이 기대한 것만, 기대한 순서로 근거를 낸다. 기간 밖 인자는 시도만 있고 근거를 내지 않는다.
        if (c.expectedStatus().equals("INSUFFICIENT_EVIDENCE")) {
            assertThat(fakes.executed).as(c.id()).isEmpty();
        } else {
            assertThat(fakes.executed).as(c.id()).containsExactlyElementsOf(c.expectedTools());
        }

        // 모델 입력(질문·이력·근거·도구 명세) 어디에도 토큰·backtestId 가 없다.
        String everything =
                fakes.turns.stream()
                        .map(t -> t.conversation() + " " + t.facts() + " " + t.tools())
                        .reduce("", String::concat);
        assertThat(everything).doesNotContain(TOKEN, PARTIAL_TOKEN, BACKTEST_ID, "backtestId");
    }

    @Test
    @DisplayName("표본은 결과 질문 20개(절반은 도구 필요)와 경계 10개다")
    void sampleShape() throws IOException {
        List<Case> all = cases();
        List<Case> result = all.stream().filter(c -> c.group().equals("result")).toList();
        List<Case> boundary = all.stream().filter(c -> c.group().equals("boundary")).toList();

        assertThat(result).hasSize(20);
        assertThat(boundary).hasSize(10);
        assertThat(result.stream().filter(c -> !c.expectedTools().isEmpty())).hasSize(10);
        assertThat(all.stream().map(Case::id).distinct()).hasSize(30);
    }

    @Test
    @DisplayName("모르는 도구 이름은 실행되지 않고 근거 부족으로 끝난다")
    void unknownToolIsNeverExecuted() throws Exception {
        fakes.script =
                turn ->
                        turn.conversation().exchanges().isEmpty()
                                ? new ToolRequests(
                                        List.of(
                                                new ToolCall(
                                                        "c1",
                                                        "getSecret",
                                                        Map.of("date", "2024-03-04"))))
                                : new ModelDraft(AnswerStatus.INSUFFICIENT_EVIDENCE, List.of());

        ask("비밀 알려 줘", TOKEN)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("INSUFFICIENT_EVIDENCE"));

        assertThat(fakes.tools).allSatisfy(tool -> assertThat(tool.attempts).isEmpty());
        assertThat(fakes.turns).hasSize(2);
    }

    @Test
    @DisplayName("식별자를 담은 인자는 실행되지 않는다")
    void identifierArgumentIsRejected() throws Exception {
        fakes.script =
                turn ->
                        turn.conversation().exchanges().isEmpty()
                                ? new ToolRequests(
                                        List.of(
                                                new ToolCall(
                                                        "c1",
                                                        "getEquityOn",
                                                        Map.of(
                                                                "date",
                                                                "2024-03-04",
                                                                "backtestId",
                                                                BACKTEST_ID))))
                                : new ModelDraft(AnswerStatus.INSUFFICIENT_EVIDENCE, List.of());

        ask("자산은?", TOKEN).andExpect(status().isOk());

        assertThat(fakes.tools).allSatisfy(tool -> assertThat(tool.attempts).isEmpty());
    }

    @Test
    @DisplayName("만료·없는·형식 오류 토큰은 모델을 부르지 않는다")
    void badTokensNeverReachModel() throws Exception {
        when(publicResults.resolve("bad"))
                .thenThrow(new BusinessException(ErrorCode.INVALID_INPUT));
        when(publicResults.resolve(TOKEN.replace('0', 'f')))
                .thenThrow(new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        when(publicResults.resolve(TOKEN.replace('0', 'e')))
                .thenThrow(new BusinessException(ErrorCode.RESULT_EXPIRED));

        ask("수익률은?", "bad").andExpect(status().isBadRequest());
        ask("수익률은?", TOKEN.replace('0', 'f')).andExpect(status().isNotFound());
        ask("수익률은?", TOKEN.replace('0', 'e')).andExpect(status().isGone());

        assertThat(fakes.turns).isEmpty();
    }

    @Test
    @DisplayName("모델 장애는 503 ASSISTANT_UNAVAILABLE 이고 대체 답이 없다")
    void modelFailureMapsTo503() throws Exception {
        fakes.script =
                turn -> {
                    throw new BusinessException(ErrorCode.ASSISTANT_UNAVAILABLE);
                };

        ask("수익률은?", TOKEN)
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error.code").value("ASSISTANT_UNAVAILABLE"))
                .andExpect(jsonPath("$.data").isEmpty());
    }
}
