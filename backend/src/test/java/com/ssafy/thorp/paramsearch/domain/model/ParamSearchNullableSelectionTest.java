package com.ssafy.thorp.paramsearch.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.ssafy.thorp.paramsearch.application.result.SelectionResult;
import com.ssafy.thorp.paramsearch.domain.vo.SearchSpace;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 묶음이 들고 다니는 선정 결과도 <b>값이 없다는 사실 자체가 뜻</b>이다({@link SearchCandidateNullableMapTest}의 짝).
 *
 * <p>{@code SelectionResult.note}는 "표시 수를 채우지 못한 이유"라 채울 이유가 없으면 null이다. 그래서 방어 복사가 null 값을 담을 수
 * 있어야 한다 — {@code Map.copyOf}는 null 값을 거부한다.
 *
 * <p><b>이 테스트가 생긴 이유</b>: 같은 결함을 후보 쪽({@code SearchCandidate})에서 한 번 고쳤을 때 묶음 쪽({@code
 * ParamSearch.selection})이 함께 고쳐지지 않았다. 그 뒤로 {@code note}가 비는 묶음마다 선정 저장이 {@code
 * NullPointerException}으로 죽었는데, 예외가 나는 자리가 {@code BacktestFinishedListener}의
 * {@code @TransactionalEventListener(AFTER_COMMIT)} 안이라 전파되지 않고 로그만 남았다. <b>테스트는 초록인 채로 선정이 저장되지
 * 않았고</b> 후보마다 찍힌 스택이 테스트 출력 파일을 1.3GB까지 부풀렸다.
 *
 * <p>그래서 이 보장을 통합 테스트가 아니라 도메인 단위 테스트로 둔다. {@code AFTER_COMMIT}을 지나는 경로에서는 실패가 조용하므로, 조용해질 수 없는
 * 자리에서 막아야 같은 결함이 다시 들어올 때 드러난다.
 */
class ParamSearchNullableSelectionTest {

    private static final Instant NOW = Instant.parse("2026-09-10T03:00:00Z");

    @Test
    @DisplayName("채울 이유가 없어 note가 비어도 선정 결과가 붙는다")
    void selectionWithoutNoteIsStored() {
        ParamSearch completed = completedSearch();
        Map<String, Object> selection = selectionWithoutNote();

        assertThatCode(() -> completed.withSelection(selection)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("비어 있는 note의 키는 지우지 않고 그대로 남긴다")
    void theEmptyNoteKeepsItsKey() {
        ParamSearch selected = completedSearch().withSelection(selectionWithoutNote());

        assertThat(selected.getSelection())
                .as("키를 지우면 화면이 '사유 없음'과 '필드가 아예 없음'을 구분할 수 없다")
                .containsKey("note");
        assertThat(selected.getSelection().get("note")).isNull();
    }

    @Test
    @DisplayName("계약 §3의 키 순서를 지킨다")
    void theKeyOrderFollowsTheContract() {
        ParamSearch selected = completedSearch().withSelection(selectionWithoutNote());

        assertThat(selected.getSelection().keySet())
                .containsExactly(
                        "displayed", "shortOfDisplayCount", "note", "drawdownFilterApplied");
    }

    @Test
    @DisplayName("붙인 맵은 밖에서 바꿀 수 없다")
    void theStoredMapIsNotWritable() {
        Map<String, Object> mutable = new LinkedHashMap<>(selectionWithoutNote());
        ParamSearch selected = completedSearch().withSelection(mutable);

        mutable.put("note", "나중에 끼워 넣은 사유");

        assertThat(selected.getSelection().get("note")).as("방어 복사가 없으면 저장된 뒤에도 값이 바뀐다").isNull();
    }

    /** 기준을 충족한 후보가 표시 수만큼 있어 따로 밝힐 이유가 없는 선정 결과. {@code note}가 null이다. */
    private static Map<String, Object> selectionWithoutNote() {
        return new SelectionResult(List.of(1, 2), false, null, true, Map.of())
                .toMap(List.of("PS-20260910-000001-C001", "PS-20260910-000001-C002"));
    }

    private static ParamSearch completedSearch() {
        return newSearch().markRunning(NOW).markCompleted(NOW.plusSeconds(60));
    }

    private static ParamSearch newSearch() {
        return ParamSearch.create(
                null,
                List.of("SECTOR_MOMENTUM"),
                List.of("091160.XKRX", "091170.XKRX"),
                "PROVISIONAL-20260910",
                SearchSpace.of(
                        Map.of(
                                "SECTOR_MOMENTUM",
                                SearchSpace.StrategySpace.of(
                                        "섹터 모멘텀",
                                        List.of(
                                                SearchSpace.Axis.of(
                                                        "lookbackDays", List.of(20, 60, 120)),
                                                SearchSpace.Axis.of("topN", List.of(2, 3)))))),
                newSelectionRule(),
                null,
                Map.of("maxCandidates", 1000),
                Map.of("engineVersion", "0.1.0"),
                Map.of("initialCash", "10000000.00"),
                SearchCounts.initial(6, 6, 0));
    }

    private static SelectionRule newSelectionRule() {
        return new SelectionRule(
                new SelectionRule.DrawdownFilter("NOT_WORSE_THAN_BASELINE", "EQUAL_WEIGHT"),
                new SelectionRule.StabilityFilter("BATCH_PERCENTILE", 50),
                "NET_RETURN",
                5,
                "SHOW_ALL_TIED",
                new SelectionRule.StabilityFormula(
                        "SINGLE_AXIS_ONE_STEP", "MEAN_ABS_NET_RETURN_DIFF"),
                List.of("displayCount"));
    }
}
