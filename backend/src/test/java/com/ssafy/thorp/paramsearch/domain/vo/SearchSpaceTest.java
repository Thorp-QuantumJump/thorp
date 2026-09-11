package com.ssafy.thorp.paramsearch.domain.vo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** 탐색 공간의 불변식. 전략마다 축 구성이 달라도 같은 절차로 조합 수가 나와야 한다(FR-027·FR-029). */
class SearchSpaceTest {

    @Nested
    @DisplayName("조합 수")
    class CombinationCount {

        @Test
        @DisplayName("전략별 (축별 값 개수의 곱)의 합이다")
        void sumsProductPerStrategy() {
            SearchSpace space =
                    SearchSpace.of(
                            ordered(
                                    "SECTOR_MOMENTUM",
                                    strategy(
                                            axis("lookbackDays", List.of(20, 60, 120)),
                                            axis("topN", List.of(2, 3))),
                                    "MIN_VARIANCE",
                                    strategy(axis("window", List.of(60, 120, 250, 500)))));

            // 3 * 2 + 4 — 축 개수가 전략마다 달라도 같은 절차다.
            assertThat(space.combinationCount()).isEqualTo(10L);
        }

        @Test
        @DisplayName("축이 없는 전략도 조합 1건이다")
        void strategyWithoutAxesCountsAsOne() {
            SearchSpace space = SearchSpace.of(Map.of("EQUAL_WEIGHT", strategy()));

            assertThat(space.combinationCount()).isEqualTo(1L);
        }

        @Test
        @DisplayName("전략이 없으면 0이다 — 조합 생성 전에 거부된 묶음이다")
        void emptySpaceCountsZero() {
            SearchSpace space = SearchSpace.of(Map.of());

            assertThat(space.isEmpty()).isTrue();
            assertThat(space.combinationCount()).isZero();
        }

        @Test
        @DisplayName("곱이 long을 넘으면 예외로 드러낸다")
        void overflowThrows() {
            List<SearchSpace.Axis> axes = new ArrayList<>();
            for (int i = 0; i < 40; i++) {
                axes.add(axis("axis" + i, List.of(1, 2, 3, 4)));
            }
            SearchSpace space =
                    SearchSpace.of(Map.of("WIDE", SearchSpace.StrategySpace.of(null, axes)));

            assertThatThrownBy(space::combinationCount)
                    .isInstanceOf(ArithmeticException.class)
                    .hasMessageContaining("overflow");
        }
    }

    @Nested
    @DisplayName("순서와 불변식")
    class Invariants {

        @Test
        @DisplayName("축 순서와 축별 값 순서를 넣은 그대로 보존한다")
        void keepsInsertionOrder() {
            SearchSpace space =
                    SearchSpace.of(
                            Map.of(
                                    "SECTOR_MOMENTUM",
                                    strategy(
                                            axis("topN", List.of(4, 2, 3)),
                                            axis("lookbackDays", List.of(120, 20)))));

            assertThat(space.axisNames("SECTOR_MOMENTUM")).containsExactly("topN", "lookbackDays");
            // 007이 값을 정렬하지 않는다 — 등록표가 준 순서가 그리드 좌표의 뜻이다(FR-025·FR-029).
            assertThat(space.valuesOf("SECTOR_MOMENTUM", "topN")).containsExactly(4, 2, 3);
        }

        @Test
        @DisplayName("같은 전략 안의 축 이름이 겹치면 거부한다")
        void rejectsDuplicateAxis() {
            assertThatThrownBy(
                            () ->
                                    strategy(
                                            axis("topN", List.of(2, 3)),
                                            axis("topN", List.of(4, 5))))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("duplicate axis");
        }

        @Test
        @DisplayName("전략이 달라 축 이름이 같아도 서로 간섭하지 않는다")
        void sameAxisNameAcrossStrategiesIsIndependent() {
            SearchSpace space =
                    SearchSpace.of(
                            ordered(
                                    "A",
                                    strategy(axis("window", List.of(20, 60))),
                                    "B",
                                    strategy(axis("window", List.of(120, 250, 500)))));

            assertThat(space.valuesOf("A", "window")).containsExactly(20, 60);
            assertThat(space.valuesOf("B", "window")).containsExactly(120, 250, 500);
            assertThat(space.combinationCount()).isEqualTo(5L);
        }

        @Test
        @DisplayName("모르는 전략·축 조회는 예외다")
        void unknownLookupThrows() {
            SearchSpace space = SearchSpace.of(Map.of("A", strategy(axis("topN", List.of(2)))));

            assertThatThrownBy(() -> space.strategySpace("B"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("unknown strategy");
            assertThatThrownBy(() -> space.valuesOf("A", "lookbackDays"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("unknown axis");
        }

        @Test
        @DisplayName("값이 없거나 중복인 축은 거부한다")
        void rejectsBadAxisValues() {
            assertThatThrownBy(() -> axis("topN", List.of()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("at least one value");
            assertThatThrownBy(() -> axis("topN", List.of(2, 2)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("duplicates");
        }
    }

    private static SearchSpace.Axis axis(String name, List<Object> values) {
        return SearchSpace.Axis.of(name, values);
    }

    private static SearchSpace.StrategySpace strategy(SearchSpace.Axis... axes) {
        return SearchSpace.StrategySpace.of(null, List.of(axes));
    }

    /** 전략 순서를 확인하는 경우를 위해 순서 있는 맵으로 만든다({@code Map.of()}는 순서가 없다). */
    private static Map<String, SearchSpace.StrategySpace> ordered(
            String firstId,
            SearchSpace.StrategySpace first,
            String secondId,
            SearchSpace.StrategySpace second) {
        Map<String, SearchSpace.StrategySpace> strategies = new LinkedHashMap<>();
        strategies.put(firstId, first);
        strategies.put(secondId, second);
        return strategies;
    }
}
