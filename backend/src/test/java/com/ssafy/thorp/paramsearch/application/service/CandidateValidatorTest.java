package com.ssafy.thorp.paramsearch.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ssafy.thorp.paramsearch.application.service.CandidateValidator.ValidatedPoint;
import com.ssafy.thorp.paramsearch.application.service.GridExpander.GridPoint;
import com.ssafy.thorp.paramsearch.domain.vo.SearchSpace;
import com.ssafy.thorp.paramsearch.registry.RegistryFixtures;
import com.ssafy.thorp.strategy.application.exception.RegistryUnavailableException;
import com.ssafy.thorp.strategy.application.port.ParameterRegistryPort;
import com.ssafy.thorp.strategy.application.port.SearchContext;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 유효성 사전 제외(T089).
 *
 * <p>확인하는 것은 "007이 판정하지 않는다"이다 — 어떤 조합이 제외되는지는 fixture 등록표가 정하고, 007은 사유 코드를 그대로 옮긴다(FR-006,
 * FR-025, SC-002).
 */
class CandidateValidatorTest {

    private static final String STRATEGY = "STRATEGY_A";

    private final GridExpander expander = new GridExpander();

    @Test
    @DisplayName("등록표가 거부하는 조합을 모두 제외하고 사유 코드를 그대로 남긴다")
    void excludesEveryCombinationTheRegistryRejects() {
        // pick > universeSize 를 거부하는 등록표. 이 규칙은 fixture에만 있고 007 코드에는 없다.
        ParameterRegistryPort registry =
                RegistryFixtures.registry("FIXTURE-1")
                        .strategy(STRATEGY, RegistryFixtures.axis("pick", List.of(1, 2, 3, 4)))
                        .rule(
                                STRATEGY,
                                "PICK_EXCEEDS_UNIVERSE",
                                (parameters, context) ->
                                        (int) parameters.get("pick") > context.universeSize())
                        .build();
        List<GridPoint> points = expander.expand(space(List.of(1, 2, 3, 4)));

        List<ValidatedPoint> validated =
                new CandidateValidator(registry).validate(points, new SearchContext(2));

        assertThat(validated).hasSize(4);
        assertThat(validated).filteredOn(ValidatedPoint::isValid).hasSize(2);
        assertThat(validated)
                .filteredOn(point -> !point.isValid())
                .allSatisfy(
                        point -> {
                            assertThat(point.verdict().reasonCode())
                                    .isEqualTo("PICK_EXCEEDS_UNIVERSE");
                            assertThat(point.verdict().reasonDetail()).isNotBlank();
                        });
        // 100% 제외 — 규칙을 어긴 조합이 하나도 살아남지 않는다(SC-002).
        assertThat(validated)
                .filteredOn(ValidatedPoint::isValid)
                .allSatisfy(
                        point ->
                                assertThat((int) point.point().parameters().valueOf("pick"))
                                        .isLessThanOrEqualTo(2));
    }

    @Test
    @DisplayName("규칙이 없으면 아무것도 제외하지 않는다 — 007이 제외 기준을 만들어 내지 않는다")
    void keepsEverythingWhenRegistryHasNoRule() {
        ParameterRegistryPort registry =
                RegistryFixtures.registry("FIXTURE-1")
                        .strategy(STRATEGY, RegistryFixtures.axis("pick", List.of(1, 2, 3, 4)))
                        .build();

        List<ValidatedPoint> validated =
                new CandidateValidator(registry)
                        .validate(
                                expander.expand(space(List.of(1, 2, 3, 4))), new SearchContext(1));

        assertThat(validated).allMatch(ValidatedPoint::isValid);
    }

    @Test
    @DisplayName("판정 순서가 입력 순서 그대로다 — 그 순서가 후보 순번이다")
    void keepsInputOrder() {
        ParameterRegistryPort registry =
                RegistryFixtures.registry("FIXTURE-1")
                        .strategy(STRATEGY, RegistryFixtures.axis("pick", List.of(1, 2, 3)))
                        .build();
        List<GridPoint> points = expander.expand(space(List.of(1, 2, 3)));

        assertThat(new CandidateValidator(registry).validate(points, new SearchContext(3)))
                .extracting(ValidatedPoint::point)
                .containsExactlyElementsOf(points);
    }

    @Test
    @DisplayName("판정을 받지 못하면 유효로 넘기지 않고 503으로 끊는다")
    void registryFailureStopsValidation() {
        CandidateValidator validator =
                new CandidateValidator(RegistryFixtures.validationUnavailable());
        List<GridPoint> points = expander.expand(space(List.of(1)));

        assertThatThrownBy(() -> validator.validate(points, new SearchContext(1)))
                .isInstanceOf(RegistryUnavailableException.class);
    }

    private static SearchSpace space(List<Object> pickValues) {
        return SearchSpace.of(
                Map.of(
                        STRATEGY,
                        SearchSpace.StrategySpace.of(
                                "전략 A", List.of(SearchSpace.Axis.of("pick", pickValues)))));
    }
}
