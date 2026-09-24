package com.ssafy.thorp.assistant.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ssafy.thorp.assistant.MutableClock;
import com.ssafy.thorp.assistant.application.exception.ToolUnavailableException;
import com.ssafy.thorp.assistant.application.port.AssistantModelPort;
import com.ssafy.thorp.assistant.application.port.AssistantSettings;
import com.ssafy.thorp.assistant.application.port.AssistantTool;
import com.ssafy.thorp.assistant.application.result.AnswerStatus;
import com.ssafy.thorp.assistant.application.result.Conversation;
import com.ssafy.thorp.assistant.application.result.Evidence;
import com.ssafy.thorp.assistant.application.result.EvidenceBundle;
import com.ssafy.thorp.assistant.application.result.EvidenceKind;
import com.ssafy.thorp.assistant.application.result.ModelDraft;
import com.ssafy.thorp.assistant.application.result.ModelDraft.AnswerPart;
import com.ssafy.thorp.assistant.application.result.ModelTurn;
import com.ssafy.thorp.assistant.application.result.PageKind;
import com.ssafy.thorp.assistant.application.result.ToolCall;
import com.ssafy.thorp.assistant.application.result.ToolRequests;
import com.ssafy.thorp.assistant.application.result.ToolResult;
import com.ssafy.thorp.assistant.application.result.ToolSpec;
import com.ssafy.thorp.common.exception.BusinessException;
import com.ssafy.thorp.common.exception.ErrorCode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 도구 루프(T012). 모델은 도구를 요청만 하고, 실행은 서버가 이름·인자를 검증한 뒤 한다. */
class ToolLoopTest {

    static final AssistantSettings SETTINGS =
            new AssistantSettings(
                    Set.of(PageKind.RESULT),
                    8,
                    5,
                    1000,
                    300,
                    Duration.ofSeconds(15),
                    Duration.ofSeconds(7),
                    Duration.ofSeconds(4),
                    2,
                    60,
                    30,
                    600,
                    5);

    private final MutableClock clock = new MutableClock(Instant.parse("2026-09-24T03:00:00Z"));
    private final ToolLoop loop = new ToolLoop(SETTINGS, clock);

    /** 호출 이력을 남기는 가짜 도구. {@code produce}만큼 근거를 돌려준다. */
    static final class FakeTool implements AssistantTool {
        final List<Map<String, String>> runs = new ArrayList<>();
        private final int produce;

        FakeTool(int produce) {
            this.produce = produce;
        }

        @Override
        public ToolSpec spec() {
            return new ToolSpec("getEquityOn", "특정 날짜의 시계열 점", List.of("date"));
        }

        @Override
        public List<Evidence> run(Map<String, String> arguments) {
            runs.add(arguments);
            if ("2030-01-01".equals(arguments.get("date"))) {
                throw new ToolUnavailableException("결과 기간 밖의 날짜입니다");
            }
            return IntStream.range(0, produce).mapToObj(i -> fact("점 " + i, "100" + i)).toList();
        }
    }

    /** 턴마다 다른 응답을 내는 가짜 모델. 받은 이력·근거·시간 예산을 기록한다. */
    static final class ScriptedModel implements AssistantModelPort {
        final List<Conversation> conversations = new ArrayList<>();
        final List<List<Evidence>> facts = new ArrayList<>();
        final List<Duration> timeouts = new ArrayList<>();
        private final Function<Integer, ModelTurn> script;

        ScriptedModel(Function<Integer, ModelTurn> script) {
            this.script = script;
        }

        @Override
        public ModelTurn step(
                Conversation conversation,
                List<Evidence> facts,
                List<ToolSpec> tools,
                Duration timeout) {
            conversations.add(conversation);
            this.facts.add(facts);
            timeouts.add(timeout);
            return script.apply(conversations.size());
        }
    }

    static Evidence fact(String label, String value) {
        return new Evidence(null, label, value, null, "summary", EvidenceKind.INTEGER);
    }

    static ToolRequests request(String name, Map<String, String> arguments) {
        return new ToolRequests(List.of(new ToolCall("call-1", name, arguments)));
    }

    static ModelDraft draft() {
        return new ModelDraft(
                AnswerStatus.ANSWERED, List.of(new AnswerPart("{{F1}}", List.of("F1"))));
    }

    static EvidenceBundle bundle(List<Evidence> facts, AssistantTool... tools) {
        return new EvidenceBundle(facts, List.of(tools), null);
    }

    @Test
    @DisplayName("초기 팩에 F1 부터 id 를 매기고, 초안이 오면 그대로 끝난다")
    void numbersInitialFactsAndReturnsDraft() {
        ScriptedModel model = new ScriptedModel(turn -> draft());

        ToolLoop.Outcome outcome =
                loop.run(model, "수익률?", bundle(List.of(fact("a", "1"), fact("b", "2"))));

        assertThat(outcome.rounds()).isZero();
        assertThat(outcome.facts()).extracting(Evidence::id).containsExactly("F1", "F2");
        assertThat(model.conversations).hasSize(1);
        assertThat(model.conversations.get(0).question()).isEqualTo("수익률?");
        assertThat(model.conversations.get(0).exchanges()).isEmpty();
    }

    @Test
    @DisplayName("목록에 없는 도구 이름은 실행 0회이고 '사용할 수 없음'이 다음 턴 이력에 실린다")
    void unknownToolIsNotExecuted() {
        FakeTool tool = new FakeTool(1);
        ScriptedModel model =
                new ScriptedModel(
                        turn ->
                                turn == 1
                                        ? request("deleteEverything", Map.of("date", "2024-01-02"))
                                        : draft());

        ToolLoop.Outcome outcome = loop.run(model, "q", bundle(List.of(fact("a", "1")), tool));

        assertThat(tool.runs).isEmpty();
        assertThat(outcome.rounds()).isEqualTo(1);
        List<ToolResult> results = model.conversations.get(1).exchanges().get(0).results();
        assertThat(results).hasSize(1);
        assertThat(results.get(0).unavailable()).isTrue();
        assertThat(results.get(0).unavailableReason()).startsWith(ToolLoop.UNAVAILABLE_PREFIX);
        assertThat(results.get(0).callId()).isEqualTo("call-1");
    }

    @Test
    @DisplayName("스키마 밖 인자(식별자 필드 포함)나 날짜가 아닌 값은 실행하지 않고 '사용할 수 없음'을 돌려준다")
    void rejectsArgumentsOutsideSchema() {
        FakeTool tool = new FakeTool(1);
        ScriptedModel model =
                new ScriptedModel(
                        turn ->
                                switch (turn) {
                                    case 1 ->
                                            request(
                                                    "getEquityOn",
                                                    Map.of(
                                                            "date",
                                                            "2024-01-02",
                                                            "backtestId",
                                                            "BT-1"));
                                    case 2 -> request("getEquityOn", Map.of("date", "어제"));
                                    default -> draft();
                                });

        ToolLoop.Outcome outcome = loop.run(model, "q", bundle(List.of(fact("a", "1")), tool));

        assertThat(tool.runs).isEmpty();
        assertThat(outcome.rounds()).isEqualTo(2);
        for (Conversation.Exchange exchange : model.conversations.get(2).exchanges()) {
            assertThat(exchange.results().get(0).unavailable()).isTrue();
        }
    }

    @Test
    @DisplayName("도구가 기간 밖이라고 알리면 실행한 것으로 치지 않고 이유가 모델에 돌아간다")
    void toolUnavailableExceptionBecomesUnavailableResult() {
        FakeTool tool = new FakeTool(1);
        ScriptedModel model =
                new ScriptedModel(
                        turn ->
                                turn == 1
                                        ? request("getEquityOn", Map.of("date", "2030-01-01"))
                                        : draft());

        ToolLoop.Outcome outcome = loop.run(model, "q", bundle(List.of(fact("a", "1")), tool));

        ToolResult result = model.conversations.get(1).exchanges().get(0).results().get(0);
        assertThat(result.unavailableReason())
                .isEqualTo(ToolLoop.UNAVAILABLE_PREFIX + "결과 기간 밖의 날짜입니다");
        assertThat(outcome.facts()).hasSize(1);
    }

    @Test
    @DisplayName("실행된 도구의 근거는 이어서 번호가 매겨지고 다음 턴의 팩에 들어 있다")
    void executedToolFactsJoinThePack() {
        FakeTool tool = new FakeTool(2);
        ScriptedModel model =
                new ScriptedModel(
                        turn ->
                                turn == 1
                                        ? request("getEquityOn", Map.of("date", "2024-01-02"))
                                        : draft());

        ToolLoop.Outcome outcome = loop.run(model, "q", bundle(List.of(fact("a", "1")), tool));

        assertThat(tool.runs).containsExactly(Map.of("date", "2024-01-02"));
        assertThat(outcome.facts()).extracting(Evidence::id).containsExactly("F1", "F2", "F3");
        assertThat(model.facts.get(1)).hasSize(3);
        ToolResult result = model.conversations.get(1).exchanges().get(0).results().get(0);
        assertThat(result.facts()).extracting(Evidence::id).containsExactly("F2", "F3");
    }

    @Test
    @DisplayName("3번째 도구 요청은 끊고 근거 부족으로 끝난다 — 모델 호출은 3회")
    void thirdToolRequestIsCutOff() {
        FakeTool tool = new FakeTool(1);
        ScriptedModel model =
                new ScriptedModel(turn -> request("getEquityOn", Map.of("date", "2024-01-02")));

        ToolLoop.Outcome outcome = loop.run(model, "q", bundle(List.of(fact("a", "1")), tool));

        assertThat(model.conversations).hasSize(3);
        assertThat(tool.runs).hasSize(2);
        assertThat(outcome.rounds()).isEqualTo(2);
        assertThat(outcome.draft().status()).isEqualTo(AnswerStatus.INSUFFICIENT_EVIDENCE);
        assertThat(outcome.draft().parts()).isEmpty();
    }

    @Test
    @DisplayName("팩 60개를 넘으면 도구 결과의 뒤를 자르고 '일부만 읽음' 근거를 남기며 초기 팩은 보존한다")
    void truncatesToolFactsAtPackCapacity() {
        List<Evidence> initial =
                IntStream.range(0, 58).mapToObj(i -> fact("초기 " + i, "" + i)).toList();
        FakeTool tool = new FakeTool(10);
        ScriptedModel model =
                new ScriptedModel(
                        turn ->
                                turn == 1
                                        ? request("getEquityOn", Map.of("date", "2024-01-02"))
                                        : draft());

        ToolLoop.Outcome outcome = loop.run(model, "q", bundle(initial, tool));

        assertThat(outcome.facts()).hasSize(60);
        assertThat(outcome.facts().subList(0, 58))
                .extracting(Evidence::label)
                .allMatch(l -> l.startsWith("초기 "));
        assertThat(outcome.facts().get(58).label()).isEqualTo("점 0");
        assertThat(outcome.facts().get(59).label()).isEqualTo(ToolLoop.TRUNCATED_LABEL);
        assertThat(outcome.facts().get(59).value()).startsWith(ToolLoop.TRUNCATED_VALUE);
    }

    @Test
    @DisplayName("라운드당 근거 30개를 넘으면 그 라운드 안에서 자른다")
    void truncatesAtPerRoundLimit() {
        FakeTool tool = new FakeTool(45);
        ScriptedModel model =
                new ScriptedModel(
                        turn ->
                                turn == 1
                                        ? request("getEquityOn", Map.of("date", "2024-01-02"))
                                        : draft());

        ToolLoop.Outcome outcome = loop.run(model, "q", bundle(List.of(fact("a", "1")), tool));

        assertThat(outcome.facts()).hasSize(31);
        assertThat(outcome.facts().get(30).label()).isEqualTo(ToolLoop.TRUNCATED_LABEL);
    }

    @Test
    @DisplayName("초기 팩이 60을 넘어도 잘라서 넘긴다")
    void truncatesOversizedInitialPack() {
        List<Evidence> initial =
                IntStream.range(0, 70).mapToObj(i -> fact("초기 " + i, "" + i)).toList();
        ScriptedModel model = new ScriptedModel(turn -> draft());

        ToolLoop.Outcome outcome = loop.run(model, "q", bundle(initial));

        assertThat(outcome.facts()).hasSize(60);
        assertThat(outcome.facts().get(59).label()).isEqualTo(ToolLoop.TRUNCATED_LABEL);
    }

    @Test
    @DisplayName("라운드별 시간 예산은 첫 호출 7초, 이후 4초이며 전체 15초의 남은 시간을 넘지 않는다")
    void roundBudgetsAreSlicedFromTotalDeadline() {
        FakeTool tool = new FakeTool(1);
        ScriptedModel model =
                new ScriptedModel(
                        turn -> {
                            clock.advance(Duration.ofSeconds(6));
                            return turn < 3
                                    ? request("getEquityOn", Map.of("date", "2024-01-02"))
                                    : draft();
                        });

        loop.run(model, "q", bundle(List.of(fact("a", "1")), tool));

        assertThat(model.timeouts)
                .containsExactly(
                        Duration.ofSeconds(7), Duration.ofSeconds(4), Duration.ofSeconds(3));
    }

    @Test
    @DisplayName("전체 15초를 넘기면 다음 호출 없이 503 ASSISTANT_UNAVAILABLE 이다")
    void deadlineExceededIsUnavailable() {
        FakeTool tool = new FakeTool(1);
        ScriptedModel model =
                new ScriptedModel(
                        turn -> {
                            clock.advance(Duration.ofSeconds(16));
                            return request("getEquityOn", Map.of("date", "2024-01-02"));
                        });

        assertThatThrownBy(() -> loop.run(model, "q", bundle(List.of(fact("a", "1")), tool)))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exc ->
                                assertThat(exc.getErrorCode())
                                        .isEqualTo(ErrorCode.ASSISTANT_UNAVAILABLE));
        assertThat(model.conversations).hasSize(1);
    }
}
