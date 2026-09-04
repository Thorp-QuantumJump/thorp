package com.ssafy.thorp.strategy.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.ssafy.thorp.strategy.application.command.ValidateStrategyConfigCommand;
import com.ssafy.thorp.strategy.application.port.StrategyRegistry;
import com.ssafy.thorp.strategy.application.result.StrategyConfigValidationResult;
import com.ssafy.thorp.strategy.domain.StrategyFixtures;
import com.ssafy.thorp.strategy.domain.model.ParameterViolation;
import com.ssafy.thorp.strategy.domain.model.StrategyCatalog;
import com.ssafy.thorp.strategy.domain.vo.ExecutionScope;
import com.ssafy.thorp.strategy.domain.vo.ViolationCode;
import java.math.BigDecimal;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 판정 유스케이스. 규칙 자체는 도메인 벡터 테스트가 지키고, 여기서는 조합과 출력 형태를 본다. */
class StrategyConfigValidationServiceTest {

    private static final StrategyRegistry REGISTRY =
            () -> StrategyCatalog.of(StrategyFixtures.allShippedStrategies());
    private static final StrategyConfigValidationService SERVICE =
            new StrategyConfigValidationService(REGISTRY);

    private static StrategyConfigValidationResult validate(
            String strategyId, ExecutionScope scope, Map<String, Object> parameters) {
        return SERVICE.validate(new ValidateStrategyConfigCommand(strategyId, scope, parameters));
    }

    @Test
    @DisplayName("수용되면 기본값이 채워진 확정 값 집합과 이력 요구량을 함께 돌려준다")
    void acceptedCarriesResolvedConfiguration() {
        StrategyConfigValidationResult result =
                validate(
                        "MOMENTUM",
                        ExecutionScope.BACKTEST,
                        Map.of("lookbackDays", new BigDecimal("60")));

        assertThat(result.valid()).isTrue();
        assertThat(result.definitionVersion()).isEqualTo(1);
        assertThat(result.executionScope()).isEqualTo(ExecutionScope.BACKTEST);
        assertThat(result.violations()).isEmpty();
        assertThat(result.resolvedParameters())
                .containsOnlyKeys("lookbackDays", "entryThreshold")
                .containsEntry("lookbackDays", 60L);
        assertThat((BigDecimal) result.resolvedParameters().get("entryThreshold"))
                .isEqualByComparingTo("0");
        assertThat(result.requiredHistoryBars()).isEqualTo(61);
    }

    @Test
    @DisplayName("거부되면 확정 값과 이력 요구량은 비고, 위반만 남는다")
    void rejectedCarriesOnlyViolations() {
        StrategyConfigValidationResult result =
                validate(
                        "MOMENTUM",
                        ExecutionScope.BACKTEST,
                        Map.of("lookbackDays", new BigDecimal("1")));

        assertThat(result.valid()).isFalse();
        assertThat(result.resolvedParameters()).isNull();
        assertThat(result.requiredHistoryBars()).isNull();
        assertThat(result.violations())
                .extracting(ParameterViolation::code)
                .containsExactly(ViolationCode.OUT_OF_RANGE);
    }

    @Test
    @DisplayName("카탈로그에 없는 전략은 정의 버전 없이 UNKNOWN_STRATEGY만 돌려준다")
    void unknownStrategyHasNoDefinitionVersion() {
        StrategyConfigValidationResult result =
                validate("NO_SUCH_STRATEGY", ExecutionScope.BACKTEST, Map.of());

        assertThat(result.valid()).isFalse();
        assertThat(result.strategyId()).isEqualTo("NO_SUCH_STRATEGY");
        assertThat(result.definitionVersion()).isNull();
        assertThat(result.violations())
                .singleElement()
                .satisfies(
                        violation -> {
                            assertThat(violation.code()).isEqualTo(ViolationCode.UNKNOWN_STRATEGY);
                            assertThat(violation.parameterName()).isNull();
                        });
    }

    @Test
    @DisplayName("전략이 지원하지 않는 실행 목적은 파라미터를 보기 전에 거부한다")
    void unsupportedExecutionScopeIsRejectedFirst() {
        StrategyConfigValidationResult result =
                validate(
                        "MACD_SIGNAL",
                        ExecutionScope.LIVE,
                        Map.of("fastPeriod", new BigDecimal("999")));

        assertThat(result.valid()).isFalse();
        assertThat(result.definitionVersion()).isEqualTo(2);
        // 범위 위반이 있어도 보고하지 않는다. 애초에 실행할 수 없는 조합이므로 고칠 것은 실행 목적 하나다.
        assertThat(result.violations())
                .extracting(ParameterViolation::code)
                .containsExactly(ViolationCode.EXECUTION_SCOPE_NOT_SUPPORTED);
        assertThat(result.violations().get(0).detail())
                .containsEntry("requested", "LIVE")
                .containsEntry("supported", java.util.List.of("BACKTEST"));
    }

    @Test
    @DisplayName("실행 목적 밖 파라미터는 그 요청에서 정의에 없는 이름과 같다")
    void outOfScopeParameterIsUnknown() {
        StrategyRegistry registry =
                () -> StrategyCatalog.of(java.util.List.of(StrategyFixtures.scopedDemo()));
        StrategyConfigValidationService service = new StrategyConfigValidationService(registry);

        // slippageRate 는 BACKTEST 전용이다. LIVE 요청에서는 필수여도 요구하지 않고,
        StrategyConfigValidationResult live =
                service.validate(
                        new ValidateStrategyConfigCommand(
                                "SCOPED_DEMO",
                                ExecutionScope.LIVE,
                                Map.of("period", new BigDecimal("10"))));
        assertThat(live.valid()).isTrue();
        assertThat(live.resolvedParameters()).containsOnlyKeys("period");

        // 보내면 정의에 없는 파라미터로 거부된다.
        StrategyConfigValidationResult sent =
                service.validate(
                        new ValidateStrategyConfigCommand(
                                "SCOPED_DEMO",
                                ExecutionScope.LIVE,
                                Map.of(
                                        "period", new BigDecimal("10"),
                                        "slippageRate", new BigDecimal("0.001"))));
        assertThat(sent.violations())
                .singleElement()
                .satisfies(
                        violation -> {
                            assertThat(violation.code()).isEqualTo(ViolationCode.UNKNOWN_PARAMETER);
                            assertThat(violation.parameterName()).isEqualTo("slippageRate");
                        });
    }
}
