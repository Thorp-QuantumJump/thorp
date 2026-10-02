package com.ssafy.thorp.assistant.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ssafy.thorp.assistant.MutableClock;
import com.ssafy.thorp.assistant.application.port.AssistantTool;
import com.ssafy.thorp.assistant.application.result.Deadline;
import com.ssafy.thorp.assistant.application.result.Evidence;
import com.ssafy.thorp.assistant.application.result.EvidenceBundle;
import com.ssafy.thorp.assistant.application.result.PageKind;
import com.ssafy.thorp.assistant.application.result.ToolArgument;
import com.ssafy.thorp.assistant.application.result.ToolResult;
import com.ssafy.thorp.assistant.application.result.ToolSpec;
import com.ssafy.thorp.assistant.application.service.ToolLoopTest.ScriptedModel;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 열거 인자와 인자 없는 도구(spec 017 research R2, T012). 열거 값은 요청 시점에 서버가 만든 닫힌 집합이라, 목록 밖 값(초안 글 id 등)은 실행하지
 * 않는다.
 */
class ToolLoopEnumArgumentTest {
    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-02T03:00:00Z"));
    private final ToolLoop loop = new ToolLoop(ToolLoopTest.SETTINGS);

    /** 받은 인자를 남기는 가짜 도구. */
    static final class RecordingTool implements AssistantTool {
        final List<Map<String, String>> runs = new ArrayList<>();
        private final ToolSpec spec;

        RecordingTool(ToolSpec spec) {
            this.spec = spec;
        }

        @Override
        public ToolSpec spec() {
            return spec;
        }

        @Override
        public List<Evidence> run(Map<String, String> arguments) {
            runs.add(arguments);
            return List.of(ToolLoopTest.fact("글 «비중과 리밸런싱»: 문단 1", "본문"));
        }
    }

    static final ToolSpec READ_ARTICLE =
            new ToolSpec(
                    "readArticle",
                    "게시 글 본문. weights-and-rebalancing=비중과 리밸런싱",
                    List.of(ToolArgument.oneOf("articleId", List.of("weights-and-rebalancing"))));

    static final ToolSpec READ_SECTOR_LIST = new ToolSpec("readSectorList", "섹터 목록", List.of());

    private List<ToolResult> firstRoundResults(AssistantTool tool, Map<String, String> arguments) {
        ScriptedModel model =
                new ScriptedModel(
                        turn ->
                                turn == 1
                                        ? ToolLoopTest.request(tool.spec().name(), arguments)
                                        : ToolLoopTest.draft());
        loop.run(
                model,
                PageKind.STRATEGY_SETTINGS,
                "리밸런싱이 뭐야?",
                ToolLoopTest.bundle(List.of(ToolLoopTest.fact("용어: 비중", "문장")), tool),
                new Deadline(clock, ToolLoopTest.SETTINGS.totalBudget()));
        assertThat(model.conversations).hasSize(2);
        assertThat(model.conversations.get(0).kind()).isEqualTo(PageKind.STRATEGY_SETTINGS);
        return model.conversations.get(1).exchanges().get(0).results();
    }

    @Test
    @DisplayName("목록에 있는 열거 값이면 실행한다")
    void runsWithAllowedValue() {
        RecordingTool tool = new RecordingTool(READ_ARTICLE);

        List<ToolResult> results =
                firstRoundResults(tool, Map.of("articleId", "weights-and-rebalancing"));

        assertThat(tool.runs).containsExactly(Map.of("articleId", "weights-and-rebalancing"));
        assertThat(results.get(0).unavailable()).isFalse();
    }

    @Test
    @DisplayName("목록 밖 값(초안 글 id)·낯선 인자 이름은 실행 0회이고 \"사용할 수 없음\"을 돌려준다")
    void rejectsValueOutsideTheList() {
        RecordingTool tool = new RecordingTool(READ_ARTICLE);

        List<ToolResult> draftId = firstRoundResults(tool, Map.of("articleId", "backtest"));
        List<ToolResult> strangeKey =
                firstRoundResults(
                        tool, Map.of("articleId", "weights-and-rebalancing", "publicToken", "x"));

        assertThat(tool.runs).isEmpty();
        assertThat(draftId.get(0).unavailableReason())
                .startsWith(ToolLoop.UNAVAILABLE_PREFIX)
                .contains("목록에 있는 값");
        assertThat(strangeKey.get(0).unavailableReason()).startsWith(ToolLoop.UNAVAILABLE_PREFIX);
    }

    @Test
    @DisplayName("인자 없는 도구는 빈 인자로 실행하고, 인자가 오면 실행하지 않는다")
    void noArgumentTool() {
        RecordingTool tool = new RecordingTool(READ_SECTOR_LIST);

        List<ToolResult> empty = firstRoundResults(tool, Map.of());
        List<ToolResult> withArgument = firstRoundResults(tool, Map.of("sector", "반도체"));

        assertThat(tool.runs).containsExactly(Map.of());
        assertThat(empty.get(0).unavailable()).isFalse();
        assertThat(withArgument.get(0).unavailable()).isTrue();
    }

    @Test
    @DisplayName("열거 집합이 빈 인자는 만들 수 없다 — 게시 글이 없으면 그 도구를 목록에 넣지 않는다")
    void emptyEnumIsRejected() {
        assertThatThrownBy(() -> ToolArgument.oneOf("articleId", List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("결과 화면 대화는 종류를 따로 주지 않아도 RESULT 다")
    void resultConversationDefaultsToResult() {
        ScriptedModel model = new ScriptedModel(turn -> ToolLoopTest.draft());

        loop.run(
                model,
                "수익률은?",
                new EvidenceBundle(List.of(ToolLoopTest.fact("x", "1")), List.of(), null),
                new Deadline(clock, ToolLoopTest.SETTINGS.totalBudget()));

        assertThat(model.conversations.get(0).kind()).isEqualTo(PageKind.RESULT);
    }
}
