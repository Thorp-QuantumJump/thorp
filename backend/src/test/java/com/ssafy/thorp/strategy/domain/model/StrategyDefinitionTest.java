package com.ssafy.thorp.strategy.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ssafy.thorp.strategy.domain.StrategyFixtures;
import com.ssafy.thorp.strategy.domain.vo.ExecutionScope;
import com.ssafy.thorp.strategy.domain.vo.HistoryRule;
import com.ssafy.thorp.strategy.domain.vo.ParameterType;
import com.ssafy.thorp.strategy.domain.vo.ParameterValue;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 이 기능의 목표를 직접 확인하는 테스트.
 *
 * <p>전략마다 파라미터의 이름·개수·타입 구성이 달라도 모두 같은 {@code List<ParameterDefinition>}으로 표현되는지, 그래서 소비 지점이 전략별 분기
 * 없이 다룰 수 있는지를 본다.
 */
class StrategyDefinitionTest {

    @Test
    @DisplayName("전략마다 파라미터의 이름과 개수가 달라도 모두 같은 목록 형식으로 표현된다")
    void differentParameterSetsShareOneRepresentation() {
        List<StrategyDefinition> strategies = StrategyFixtures.allShippedStrategies();

        // 개수가 서로 다르다
        assertThat(strategies)
                .extracting(definition -> definition.parameters().size())
                .containsExactly(4, 4, 3, 2);

        // 이름 집합도 서로 다르다
        assertThat(strategies.get(0).parameterNames())
                .containsExactly("shortPeriod", "longPeriod", "allowShort", "rebalancePeriod");
        assertThat(strategies.get(2).parameterNames())
                .containsExactly("fastPeriod", "slowPeriod", "signalPeriod");
        assertThat(strategies.get(0).parameterNames())
                .doesNotContainAnyElementsOf(strategies.get(2).parameterNames());

        // 타입 구성도 서로 다르다
        assertThat(typesOf(strategies.get(0)))
                .containsExactlyInAnyOrder(
                        ParameterType.INTEGER, ParameterType.BOOLEAN, ParameterType.ENUM);
        assertThat(typesOf(strategies.get(1)))
                .containsExactlyInAnyOrder(ParameterType.INTEGER, ParameterType.DECIMAL);
        assertThat(typesOf(strategies.get(2))).containsExactly(ParameterType.INTEGER);

        // 그래도 모든 파라미터가 하나의 규격을 만족한다 — 전략별 분기가 필요 없다
        assertThat(strategies)
                .flatExtracting(StrategyDefinition::parameters)
                .hasSize(13)
                .allSatisfy(
                        parameter -> {
                            assertThat(parameter.name()).matches(ParameterDefinition.NAME_PATTERN);
                            assertThat(parameter.displayName()).isNotBlank();
                            assertThat(parameter.description()).isNotBlank();
                            assertThat(parameter.executionScopes()).isNotEmpty();
                            assertThat(parameter.range().isPresent())
                                    .isEqualTo(parameter.type().usesNumericRange());
                            assertThat(parameter.options().isEmpty())
                                    .isEqualTo(!parameter.type().usesEnumValues());
                        });
    }

    @Test
    @DisplayName("최소 이력 규칙 네 종류가 모두 같은 선언 형식으로 표현된다")
    void allHistoryRulesShareOneRepresentation() {
        List<MinimumHistoryRequirement> requirements =
                List.of(
                        StrategyFixtures.smaCrossover().minimumHistoryRequirement(),
                        StrategyFixtures.rsiReversion().minimumHistoryRequirement(),
                        StrategyFixtures.macdSignal().minimumHistoryRequirement(),
                        StrategyFixtures.withoutParameters().minimumHistoryRequirement());

        assertThat(requirements)
                .extracting(MinimumHistoryRequirement::rule)
                .containsExactly(
                        HistoryRule.MAX_PARAMETERS,
                        HistoryRule.PARAMETER,
                        HistoryRule.SUM_PARAMETERS,
                        HistoryRule.FIXED);
        assertThat(requirements)
                .allSatisfy(
                        requirement -> {
                            assertThat(requirement.unit())
                                    .isEqualTo(MinimumHistoryRequirement.BARS);
                            assertThat(requirement.offset()).isNotNegative();
                            assertThat(requirement.fixedValue().isPresent())
                                    .isEqualTo(requirement.rule().usesFixedValue());
                            assertThat(requirement.targetParameters().isEmpty())
                                    .isEqualTo(!requirement.rule().usesTargetParameters());
                        });
    }

    @Test
    @DisplayName("파라미터가 하나도 없는 전략도 빈 목록으로 표현되며 오류가 아니다")
    void supportsStrategyWithoutParameters() {
        StrategyDefinition definition = StrategyFixtures.withoutParameters();

        assertThat(definition.parameters()).isEmpty();
        assertThat(definition.hasParameters()).isFalse();
        assertThat(definition.parameterNames()).isEmpty();
        assertThat(definition.parameter("anything")).isEmpty();
    }

    @Test
    @DisplayName("이름으로 파라미터 정의를 찾는다")
    void findsParameterByName() {
        StrategyDefinition definition = StrategyFixtures.smaCrossover();

        assertThat(definition.parameter("shortPeriod"))
                .get()
                .satisfies(
                        parameter -> {
                            assertThat(parameter.type()).isEqualTo(ParameterType.INTEGER);
                            assertThat(parameter.required()).isTrue();
                            assertThat(parameter.hasDefault()).isFalse();
                        });
        assertThat(definition.parameter("nope")).isEmpty();
    }

    @Test
    @DisplayName("실행 목적에 해당하지 않는 파라미터는 그 목적의 목록에서 빠진다")
    void filtersParametersByExecutionScope() {
        StrategyDefinition definition = StrategyFixtures.scopedDemo();

        assertThat(definition.parametersFor(ExecutionScope.BACKTEST))
                .extracting(ParameterDefinition::name)
                .containsExactly("period", "slippageRate");
        assertThat(definition.parametersFor(ExecutionScope.LIVE))
                .extracting(ParameterDefinition::name)
                .containsExactly("period");
    }

    @Test
    @DisplayName("전략 자체가 지원하지 않는 실행 목적은 구분된다")
    void reportsSupportedExecutionScopes() {
        assertThat(StrategyFixtures.macdSignal().supports(ExecutionScope.BACKTEST)).isTrue();
        assertThat(StrategyFixtures.macdSignal().supports(ExecutionScope.LIVE)).isFalse();
        assertThat(StrategyFixtures.smaCrossover().supports(ExecutionScope.LIVE)).isTrue();
    }

    @Test
    @DisplayName("파라미터 이름이 중복되면 정의를 만들 수 없다")
    void rejectsDuplicateParameterNames() {
        StrategyDefinition source = StrategyFixtures.momentum();
        ParameterDefinition duplicated = source.parameters().get(0);

        assertThatThrownBy(
                        () ->
                                new StrategyDefinition(
                                        source.schemaVersion(),
                                        source.strategyId(),
                                        source.definitionVersion(),
                                        source.displayName(),
                                        source.description(),
                                        source.entryCondition(),
                                        source.exitCondition(),
                                        source.executionScopes(),
                                        List.of(duplicated, duplicated),
                                        source.parameterConstraints(),
                                        source.minimumHistoryRequirement(),
                                        source.changeHistory(),
                                        false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("중복");
    }

    @Test
    @DisplayName("전략이 지원하지 않는 실행 목적을 파라미터가 주장하면 정의를 만들 수 없다")
    void rejectsParameterScopeOutsideStrategyScope() {
        StrategyDefinition source = StrategyFixtures.macdSignal(); // BACKTEST 전용 전략
        ParameterDefinition liveOnly =
                new ParameterDefinition(
                        "liveOnlyFlag",
                        "실시장 전용",
                        "전략이 지원하지 않는 실행 목적을 주장한다.",
                        ParameterType.BOOLEAN,
                        false,
                        ParameterValue.of(false),
                        null,
                        Set.of(ExecutionScope.LIVE),
                        null,
                        null);

        assertThatThrownBy(
                        () ->
                                new StrategyDefinition(
                                        source.schemaVersion(),
                                        source.strategyId(),
                                        source.definitionVersion(),
                                        source.displayName(),
                                        source.description(),
                                        source.entryCondition(),
                                        source.exitCondition(),
                                        source.executionScopes(),
                                        List.of(liveOnly),
                                        List.of(),
                                        MinimumHistoryRequirement.fixed(10, 0),
                                        source.changeHistory(),
                                        false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("실행 목적을 벗어난다");
    }

    @Test
    @DisplayName("전략 식별자는 대문자 규칙을 따라야 한다")
    void rejectsInvalidStrategyId() {
        StrategyDefinition source = StrategyFixtures.momentum();

        assertThatThrownBy(
                        () ->
                                new StrategyDefinition(
                                        source.schemaVersion(),
                                        "sma_crossover",
                                        1,
                                        source.displayName(),
                                        source.description(),
                                        source.entryCondition(),
                                        source.exitCondition(),
                                        source.executionScopes(),
                                        List.of(),
                                        List.of(),
                                        source.minimumHistoryRequirement(),
                                        source.changeHistory(),
                                        false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("전략 식별자");
    }

    @Test
    @DisplayName("변경 이력의 최신 항목으로 호환성 등급을 알 수 있다")
    void exposesLatestChange() {
        assertThat(StrategyFixtures.macdSignal().latestChange().breaksExistingValues()).isTrue();
        assertThat(StrategyFixtures.smaCrossover().latestChange().breaksExistingValues()).isFalse();
    }

    @Test
    @DisplayName("정의는 불변이다")
    void isImmutable() {
        StrategyDefinition definition = StrategyFixtures.smaCrossover();

        assertThatThrownBy(() -> definition.parameters().clear())
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> definition.parameterConstraints().clear())
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> definition.changeHistory().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    private static Set<ParameterType> typesOf(StrategyDefinition definition) {
        return definition.parameters().stream()
                .map(ParameterDefinition::type)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }
}
