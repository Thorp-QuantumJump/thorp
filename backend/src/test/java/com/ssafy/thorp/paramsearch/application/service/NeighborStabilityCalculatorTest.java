package com.ssafy.thorp.paramsearch.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.ssafy.thorp.paramsearch.domain.model.CandidateStatus;
import com.ssafy.thorp.paramsearch.domain.model.SearchCandidate;
import com.ssafy.thorp.paramsearch.domain.model.ValidationLabel;
import com.ssafy.thorp.paramsearch.domain.vo.GridCoordinates;
import com.ssafy.thorp.paramsearch.domain.vo.ParameterCombination;
import com.ssafy.thorp.paramsearch.domain.vo.StabilityScore;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 인접 설정 안정성(T065, FR-013a·FR-013b·FR-029).
 *
 * <p>전략 ID·축 이름은 이 테스트가 지어낸 값이다. 운영 등록표의 전략을 쓰지 않는 이유는 등록표가 바뀌어도 이 계산 규칙은 그대로여야 하기 때문이다(FR-025).
 */
class NeighborStabilityCalculatorTest {

    private final NeighborStabilityCalculator calculator = new NeighborStabilityCalculator();

    @Test
    @DisplayName("이웃은 한 축만 한 칸 다른 후보이고, 값은 그 이웃들과의 수익 차 절댓값 평균이다")
    void averagesAbsoluteDifferenceOfSingleStepNeighbors() {
        // 1축 그리드: 0 - 1 - 2. 가운데 후보는 이웃이 둘이다.
        SearchCandidate left = completed(1L, "A", Map.of("x", 0));
        SearchCandidate middle = completed(2L, "A", Map.of("x", 1));
        SearchCandidate right = completed(3L, "A", Map.of("x", 2));
        Map<Long, BigDecimal> netReturns = returns(1L, "0.1000", 2L, "0.1500", 3L, "0.3000");

        Map<Long, StabilityScore> scores =
                calculator.calculate(List.of(left, middle, right), netReturns);

        // |0.15-0.10| = 0.05, |0.15-0.30| = 0.15 → 평균 0.10
        StabilityScore center = scores.get(2L);
        assertThat(center.calculable()).isTrue();
        assertThat(center.neighborCount()).isEqualTo(2);
        assertThat(center.value()).isEqualByComparingTo("0.10");
        // 경계 후보는 있는 이웃만 센다.
        assertThat(scores.get(1L).neighborCount()).isEqualTo(1);
        assertThat(scores.get(1L).value()).isEqualByComparingTo("0.05");
    }

    @Test
    @DisplayName("두 칸 떨어진 후보와 두 축이 동시에 다른 후보는 이웃이 아니다")
    void onlySingleAxisOneStepCounts() {
        SearchCandidate origin = completed(1L, "A", coords("x", 0, "y", 0));
        SearchCandidate twoStepsAway = completed(2L, "A", coords("x", 2, "y", 0));
        SearchCandidate diagonal = completed(3L, "A", coords("x", 1, "y", 1));

        Map<Long, StabilityScore> scores =
                calculator.calculate(
                        List.of(origin, twoStepsAway, diagonal),
                        returns(1L, "0.1000", 2L, "0.9000", 3L, "0.9000"));

        assertThat(scores.get(1L).calculable()).isFalse();
        assertThat(scores.get(1L).value()).isNull();
    }

    @Test
    @DisplayName("전략이 다른 후보는 좌표가 인접해도 이웃이 아니다")
    void neighborsNeverCrossStrategies() {
        SearchCandidate mine = completed(1L, "A", Map.of("x", 0));
        SearchCandidate sameCoordsOtherStrategy = completed(2L, "B", Map.of("x", 1));

        Map<Long, StabilityScore> scores =
                calculator.calculate(
                        List.of(mine, sameCoordsOtherStrategy),
                        returns(1L, "0.1000", 2L, "0.9000"));

        assertThat(scores.get(1L).calculable())
                .as("축 이름이 겹쳐도 전략이 다르면 좌표의 뜻이 다르다(FR-029)")
                .isFalse();
        assertThat(scores.get(2L).calculable()).isFalse();
    }

    @Test
    @DisplayName("이웃이 없으면 계산 불가이고 값은 null이다 — 0으로 채우지 않는다")
    void lonelyCandidateIsUncalculable() {
        SearchCandidate only = completed(1L, "A", Map.of("x", 0));

        StabilityScore score = calculator.calculate(List.of(only), returns(1L, "0.1000")).get(1L);

        assertThat(score.calculable()).isFalse();
        assertThat(score.value()).as("0은 '차이가 전혀 없다 = 매우 안정적'으로 읽혀 계산 불가와 정반대다(FR-013b)").isNull();
        assertThat(score.neighborCount()).isZero();
    }

    @Test
    @DisplayName("완료되지 않은 후보는 대상도 이웃도 아니다")
    void unfinishedCandidatesAreNeitherSubjectNorNeighbor() {
        SearchCandidate done = completed(1L, "A", Map.of("x", 0));
        SearchCandidate failed = candidate(2L, "A", Map.of("x", 1), CandidateStatus.FAILED);
        SearchCandidate excluded = candidate(3L, "A", Map.of("x", 2), CandidateStatus.EXCLUDED);

        Map<Long, StabilityScore> scores =
                calculator.calculate(List.of(done, failed, excluded), returns(1L, "0.1000"));

        assertThat(scores).containsOnlyKeys(1L);
        assertThat(scores.get(1L).calculable())
                .as("실패한 이웃을 0으로 두면 멀쩡한 후보가 크게 흔들린 것처럼 보인다(FR-011)")
                .isFalse();
    }

    @Test
    @DisplayName("축 값이 숫자가 아니어도 인덱스만으로 이웃을 판정한다")
    void worksOnNonNumericAxes() {
        // 좌표는 등록표 후보값의 인덱스라, 값이 "MONTH_END" 같은 문자열이어도 판정은 같다(FR-029).
        SearchCandidate first =
                completedWithParameters(
                        1L, "A", Map.of("rebalance", 0), Map.of("rebalance", "WEEK_END"));
        SearchCandidate second =
                completedWithParameters(
                        2L, "A", Map.of("rebalance", 1), Map.of("rebalance", "MONTH_END"));

        Map<Long, StabilityScore> scores =
                calculator.calculate(List.of(first, second), returns(1L, "0.1000", 2L, "0.2000"));

        assertThat(scores.get(1L).calculable()).isTrue();
        assertThat(scores.get(1L).value()).isEqualByComparingTo("0.10");
    }

    private static SearchCandidate completed(
            long id, String strategyId, Map<String, Integer> coordinates) {
        return candidate(id, strategyId, coordinates, CandidateStatus.COMPLETED);
    }

    private static SearchCandidate completedWithParameters(
            long id,
            String strategyId,
            Map<String, Integer> coordinates,
            Map<String, Object> parameters) {
        return SearchCandidate.restore()
                .id(id)
                .paramSearchId(1L)
                .candidateNo((int) id)
                .strategyId(strategyId)
                .parameters(ParameterCombination.of(parameters))
                .gridCoordinates(GridCoordinates.of(coordinates))
                .status(CandidateStatus.COMPLETED)
                .validationLabel(ValidationLabel.UNVALIDATED)
                .build();
    }

    private static SearchCandidate candidate(
            long id, String strategyId, Map<String, Integer> coordinates, CandidateStatus status) {
        return SearchCandidate.restore()
                .id(id)
                .paramSearchId(1L)
                .candidateNo((int) id)
                .strategyId(strategyId)
                .parameters(ParameterCombination.of(Map.of("x", id)))
                .gridCoordinates(GridCoordinates.of(coordinates))
                .status(status)
                .validationLabel(ValidationLabel.UNVALIDATED)
                .build();
    }

    private static Map<String, Integer> coords(String a, int av, String b, int bv) {
        Map<String, Integer> map = new LinkedHashMap<>();
        map.put(a, av);
        map.put(b, bv);
        return map;
    }

    private static Map<Long, BigDecimal> returns(Object... idThenValue) {
        Map<Long, BigDecimal> map = new LinkedHashMap<>();
        for (int i = 0; i < idThenValue.length; i += 2) {
            map.put((Long) idThenValue[i], new BigDecimal((String) idThenValue[i + 1]));
        }
        return map;
    }
}
