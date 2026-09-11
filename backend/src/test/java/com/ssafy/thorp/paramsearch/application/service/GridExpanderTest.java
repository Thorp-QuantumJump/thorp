package com.ssafy.thorp.paramsearch.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.ssafy.thorp.paramsearch.application.service.GridExpander.GridPoint;
import com.ssafy.thorp.paramsearch.domain.vo.SearchSpace;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 그리드 전개(T039). 조합 수와 좌표가 등록표 인덱스와 맞아야 재현 기록이 성립한다(FR-005·FR-029). */
class GridExpanderTest {

    private final GridExpander expander = new GridExpander();

    @Test
    @DisplayName("조합 수는 축별 값 개수의 곱이고 좌표는 값 목록의 인덱스다")
    void expandsFullGridWithRegistryIndices() {
        SearchSpace space =
                space(
                        "STRATEGY_A",
                        SearchSpace.StrategySpace.of(
                                "전략 A",
                                List.of(
                                        SearchSpace.Axis.of("lookback", List.of(20, 60, 120)),
                                        SearchSpace.Axis.of("pick", List.of(2, 3)))));

        List<GridPoint> points = expander.expand(space);

        assertThat(points).hasSize(6).hasSize((int) space.combinationCount());
        // 마지막 축이 가장 빨리 돈다 — 같은 입력이 늘 같은 순번을 만든다(FR-029).
        assertThat(points)
                .extracting(point -> point.parameters().values())
                .containsExactly(
                        values("lookback", 20, "pick", 2),
                        values("lookback", 20, "pick", 3),
                        values("lookback", 60, "pick", 2),
                        values("lookback", 60, "pick", 3),
                        values("lookback", 120, "pick", 2),
                        values("lookback", 120, "pick", 3));
        assertThat(points.get(3).coordinates().indices())
                .containsExactlyInAnyOrderEntriesOf(Map.of("lookback", 1, "pick", 1));
        assertThat(points)
                .allSatisfy(point -> assertThat(point.strategyId()).isEqualTo("STRATEGY_A"));
    }

    @Test
    @DisplayName("축 구성이 다른 전략을 섞으면 조합 수는 전략별 곱의 합이다")
    void mixedStrategiesSumTheirGrids() {
        Map<String, SearchSpace.StrategySpace> strategies = new LinkedHashMap<>();
        strategies.put(
                "STRATEGY_A",
                SearchSpace.StrategySpace.of(
                        "전략 A",
                        List.of(
                                SearchSpace.Axis.of("x", List.of(1, 2, 3)),
                                SearchSpace.Axis.of("y", List.of(1, 2)))));
        strategies.put(
                "STRATEGY_B",
                SearchSpace.StrategySpace.of(
                        "전략 B", List.of(SearchSpace.Axis.of("z", List.of(1, 2, 3, 4)))));
        strategies.put("STRATEGY_C", SearchSpace.StrategySpace.of("전략 C", List.of()));

        List<GridPoint> points = expander.expand(SearchSpace.of(strategies));

        assertThat(points).hasSize(3 * 2 + 4 + 1);
        assertThat(points).extracting(GridPoint::strategyId).startsWith("STRATEGY_A");
        // 축이 없는 전략도 조합 1건이다 — 파라미터가 없다고 계산 대상에서 빠지지 않는다.
        assertThat(points)
                .filteredOn(point -> point.strategyId().equals("STRATEGY_C"))
                .singleElement()
                .satisfies(point -> assertThat(point.parameters().isEmpty()).isTrue());
    }

    @Test
    @DisplayName("전략과 축의 순서를 탐색 공간에 담긴 그대로 따른다")
    void keepsStrategyAndAxisOrder() {
        Map<String, SearchSpace.StrategySpace> strategies = new LinkedHashMap<>();
        strategies.put(
                "STRATEGY_B",
                SearchSpace.StrategySpace.of(
                        "전략 B", List.of(SearchSpace.Axis.of("z", List.of(1)))));
        strategies.put(
                "STRATEGY_A",
                SearchSpace.StrategySpace.of(
                        "전략 A", List.of(SearchSpace.Axis.of("x", List.of(1)))));

        assertThat(expander.expand(SearchSpace.of(strategies)))
                .extracting(GridPoint::strategyId)
                .containsExactly("STRATEGY_B", "STRATEGY_A");
    }

    @Test
    @DisplayName("같은 탐색 공간은 항상 같은 후보 집합과 같은 순서를 만든다")
    void isReproducible() {
        SearchSpace space =
                space(
                        "STRATEGY_A",
                        SearchSpace.StrategySpace.of(
                                "전략 A",
                                List.of(
                                        SearchSpace.Axis.of("x", List.of(1, 2, 3)),
                                        SearchSpace.Axis.of("y", List.of("a", "b")))));

        assertThat(expander.expand(space)).isEqualTo(expander.expand(space));
    }

    private static SearchSpace space(String strategyId, SearchSpace.StrategySpace strategy) {
        return SearchSpace.of(Map.of(strategyId, strategy));
    }

    private static Map<String, Object> values(
            String firstAxis, Object first, String secondAxis, Object second) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put(firstAxis, first);
        values.put(secondAxis, second);
        return values;
    }
}
