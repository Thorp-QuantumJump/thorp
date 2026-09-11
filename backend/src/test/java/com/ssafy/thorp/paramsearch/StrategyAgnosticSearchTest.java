package com.ssafy.thorp.paramsearch;

import static org.assertj.core.api.Assertions.assertThat;

import com.ssafy.thorp.paramsearch.application.port.ParameterRegistryPort;
import com.ssafy.thorp.paramsearch.application.port.SearchContext;
import com.ssafy.thorp.paramsearch.application.service.CandidateValidator;
import com.ssafy.thorp.paramsearch.application.service.CandidateValidator.ValidatedPoint;
import com.ssafy.thorp.paramsearch.application.service.GridExpander;
import com.ssafy.thorp.paramsearch.application.service.GridExpander.GridPoint;
import com.ssafy.thorp.paramsearch.application.service.SearchTargetResolver;
import com.ssafy.thorp.paramsearch.application.service.SearchTargetResolver.SearchTargets;
import com.ssafy.thorp.paramsearch.registry.RegistryFixtures;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 전략 비의존성(US5, T128~T132).
 *
 * <p>확인하는 것 하나다 — <b>전략이 늘어나거나 축 구성이 달라져도 운영 코드가 바뀌지 않는다.</b> 그래서 이 테스트는 실제 전략 이름을 쓰지 않고 fixture
 * 등록표로만 돌아간다. 같은 코드 경로가 전략 셋을 처리하다가 넷을 처리하게 되는지를 본다(SC-010·SC-011).
 */
class StrategyAgnosticSearchTest {

    private final GridExpander expander = new GridExpander();

    @Test
    @DisplayName("활성 전략 전부가 같은 등록표 계약 경로로 처리된다 (US5 시나리오 1)")
    void everyStrategyTakesTheSamePath() {
        ParameterRegistryPort registry =
                RegistryFixtures.registry("FIXTURE-1")
                        .strategy("S1", RegistryFixtures.axisOfSize("a", 2))
                        .strategy("S2", RegistryFixtures.axisOfSize("b", 3))
                        .strategy("S3")
                        .build();
        SearchTargets targets = resolve(registry, List.of("S1", "S2", "S3"), "S1", "S2", "S3");

        List<GridPoint> points = expander.expand(targets.fullSearchSpace());

        // 전용 경로를 타는 전략이 없다 — 축이 없는 전략도 같은 절차로 조합 1건이 된다.
        assertThat(points).extracting(GridPoint::strategyId).contains("S1", "S2", "S3");
        assertThat(points).hasSize(2 + 3 + 1);
    }

    @Test
    @DisplayName("여섯 번째 전략을 더해도 운영 코드 변경 없이 동작한다 (SC-010, US5 시나리오 2)")
    void addingASixthStrategyNeedsNoCodeChange() {
        RegistryFixtures.Builder five =
                RegistryFixtures.registry("FIXTURE-1")
                        .strategy("S1", RegistryFixtures.axisOfSize("a", 2))
                        .strategy("S2", RegistryFixtures.axisOfSize("b", 2))
                        .strategy("S3", RegistryFixtures.axisOfSize("c", 2))
                        .strategy("S4", RegistryFixtures.axisOfSize("d", 2))
                        .strategy("S5", RegistryFixtures.axisOfSize("e", 2));
        List<String> sixIds = List.of("S1", "S2", "S3", "S4", "S5", "S6");

        ParameterRegistryPort withSix =
                five.strategy("S6", RegistryFixtures.axisOfSize("f", 4)).build();
        SearchTargets targets = resolve(withSix, sixIds, sixIds.toArray(String[]::new));
        List<GridPoint> points = expander.expand(targets.fullSearchSpace());

        assertThat(targets.isRejected()).isFalse();
        // 다섯 전략 2개씩 + 여섯 번째 전략 4개
        assertThat(points).hasSize(2 * 5 + 4);
        assertThat(points).extracting(GridPoint::strategyId).contains("S6");
    }

    @Test
    @DisplayName("축 개수와 후보 개수가 다른 전략을 섞어도 셈이 등록값과 맞는다 (SC-011, US5 시나리오 3)")
    void mixedShapesCountCorrectly() {
        ParameterRegistryPort registry =
                RegistryFixtures.registry("FIXTURE-1")
                        .strategy(
                                "S1",
                                RegistryFixtures.axisOfSize("a", 3),
                                RegistryFixtures.axisOfSize("b", 4))
                        .strategy("S2", RegistryFixtures.axisOfSize("c", 5))
                        .strategy("S3")
                        // S1의 a축이 0인 조합만 제외한다. 규칙은 fixture에만 있다.
                        .rule(
                                "S1",
                                "A_IS_ZERO",
                                (combination, context) -> combination.valueOf("a").equals(0))
                        .build();
        SearchTargets targets = resolve(registry, List.of("S1", "S2", "S3"), "S1", "S2", "S3");

        List<GridPoint> points = expander.expand(targets.fullSearchSpace());
        List<ValidatedPoint> validated =
                new CandidateValidator(registry).validate(points, new SearchContext(4));

        long expectedTotal = 3L * 4 + 5 + 1;
        long expectedExcluded = 4; // a=0인 b축 4개
        assertThat(targets.fullSearchSpace().combinationCount()).isEqualTo(expectedTotal);
        assertThat(points).hasSize((int) expectedTotal);
        assertThat(validated).filteredOn(point -> !point.isValid()).hasSize((int) expectedExcluded);
        assertThat(validated)
                .filteredOn(ValidatedPoint::isValid)
                .hasSize((int) (expectedTotal - expectedExcluded));
    }

    @Test
    @DisplayName("같은 등록표 버전·같은 선택 범위는 전략을 섞어도 같은 후보 집합을 만든다 (SC-004, US5 시나리오 5)")
    void sameVersionProducesSameCandidates() {
        ParameterRegistryPort registry =
                RegistryFixtures.registry("FIXTURE-1")
                        .strategy(
                                "S1",
                                RegistryFixtures.axisOfSize("a", 3),
                                RegistryFixtures.axisOfSize("b", 2))
                        .strategy("S2", RegistryFixtures.axisOfSize("c", 4))
                        .build();
        List<String> requested = List.of("S1", "S2");

        List<GridPoint> first =
                expander.expand(resolve(registry, requested, "S1", "S2").fullSearchSpace());
        List<GridPoint> second =
                expander.expand(resolve(registry, requested, "S1", "S2").fullSearchSpace());

        // 조합도 순서도 같다 — 순번이 흔들리면 재현 기록이 거짓이 된다(FR-029).
        assertThat(second).isEqualTo(first);
    }

    private static SearchTargets resolve(
            ParameterRegistryPort registry, List<String> requested, String... supported) {
        return new SearchTargetResolver(registry, RegistryFixtures.supporting(supported))
                .resolve(requested);
    }
}
