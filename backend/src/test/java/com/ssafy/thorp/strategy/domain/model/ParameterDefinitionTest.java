package com.ssafy.thorp.strategy.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ssafy.thorp.strategy.domain.vo.EnumOption;
import com.ssafy.thorp.strategy.domain.vo.ExecutionScope;
import com.ssafy.thorp.strategy.domain.vo.NumericRange;
import com.ssafy.thorp.strategy.domain.vo.ParameterType;
import com.ssafy.thorp.strategy.domain.vo.ParameterValue;
import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** 타입이 달라도 같은 규격 안에 담기는지, 그리고 그 규격이 어긋난 조합을 막는지 확인한다. */
class ParameterDefinitionTest {

    private static final Set<ExecutionScope> BOTH =
            Set.of(ExecutionScope.BACKTEST, ExecutionScope.LIVE);

    private static ParameterDefinition of(
            String name,
            ParameterType type,
            ParameterValue defaultValue,
            NumericRange range,
            List<EnumOption> options) {
        // 기본값이 없으면 필수로 둔다. 선택 파라미터는 기본값을 반드시 가져야 하기 때문이다.
        return new ParameterDefinition(
                name,
                "표시 이름",
                "설명",
                type,
                defaultValue == null,
                defaultValue,
                null,
                BOTH,
                range,
                options);
    }

    @Test
    @DisplayName("네 타입 모두 같은 규격으로 표현된다")
    void allFourTypesShareOneShape() {
        List<ParameterDefinition> parameters =
                List.of(
                        of(
                                "period",
                                ParameterType.INTEGER,
                                ParameterValue.of(20L),
                                NumericRange.closed(BigDecimal.ONE, new BigDecimal("100")),
                                null),
                        of(
                                "threshold",
                                ParameterType.DECIMAL,
                                ParameterValue.of(new BigDecimal("0.5")),
                                NumericRange.closed(BigDecimal.ZERO, BigDecimal.ONE),
                                null),
                        of("enabled", ParameterType.BOOLEAN, ParameterValue.of(true), null, null),
                        of(
                                "mode",
                                ParameterType.ENUM,
                                ParameterValue.ofEnum("A"),
                                null,
                                List.of(new EnumOption("A", "가"), new EnumOption("B", "나"))));

        // 타입이 달라도 목록의 원소 타입은 하나다. 소비 지점이 type 만 보고 분기할 수 있다.
        assertThat(parameters)
                .hasSize(4)
                .allSatisfy(
                        parameter -> {
                            assertThat(parameter.name()).isNotBlank();
                            assertThat(parameter.displayName()).isNotBlank();
                            assertThat(parameter.description()).isNotBlank();
                            assertThat(parameter.type()).isNotNull();
                            assertThat(parameter.executionScopes()).isNotEmpty();
                            assertThat(parameter.range().isPresent())
                                    .isEqualTo(parameter.type().usesNumericRange());
                            assertThat(parameter.options().isEmpty())
                                    .isEqualTo(!parameter.type().usesEnumValues());
                        });
    }

    @Test
    @DisplayName("수치형은 유효 범위가 있어야 한다")
    void numericTypeRequiresRange() {
        assertThatThrownBy(() -> of("period", ParameterType.INTEGER, null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("numericRange");
    }

    @Test
    @DisplayName("열거형은 유효 범위를 가질 수 없다")
    void enumTypeRejectsNumericRange() {
        assertThatThrownBy(
                        () ->
                                of(
                                        "mode",
                                        ParameterType.ENUM,
                                        null,
                                        NumericRange.atLeast(BigDecimal.ONE),
                                        List.of(new EnumOption("A", "가"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("numericRange");
    }

    @Test
    @DisplayName("논리형은 범위도 허용 값 목록도 가질 수 없다")
    void booleanTypeRejectsBothRangeForms() {
        assertThatThrownBy(
                        () ->
                                of(
                                        "enabled",
                                        ParameterType.BOOLEAN,
                                        null,
                                        NumericRange.atLeast(BigDecimal.ONE),
                                        null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                of(
                                        "enabled",
                                        ParameterType.BOOLEAN,
                                        null,
                                        null,
                                        List.of(new EnumOption("A", "가"))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("열거형은 허용 값이 하나 이상이어야 한다")
    void enumTypeRequiresAtLeastOneOption() {
        assertThatThrownBy(() -> of("mode", ParameterType.ENUM, null, null, List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("허용 값");
    }

    @Test
    @DisplayName("열거형의 허용 값은 중복될 수 없다")
    void enumOptionsMustBeUnique() {
        assertThatThrownBy(
                        () ->
                                of(
                                        "mode",
                                        ParameterType.ENUM,
                                        null,
                                        null,
                                        List.of(
                                                new EnumOption("A", "가"),
                                                new EnumOption("A", "다른 이름"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("중복");
    }

    @Test
    @DisplayName("기본값의 타입이 파라미터 타입과 다르면 거부한다")
    void defaultValueTypeMustMatch() {
        assertThatThrownBy(
                        () ->
                                of(
                                        "period",
                                        ParameterType.INTEGER,
                                        ParameterValue.of(true),
                                        NumericRange.atLeast(BigDecimal.ONE),
                                        null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("기본값의 타입");
    }

    @ParameterizedTest
    @ValueSource(strings = {"ShortPeriod", "short_period", "1period", "", "short-period"})
    @DisplayName("파라미터 이름은 camelCase 규칙을 따라야 한다")
    void rejectsInvalidNames(String name) {
        assertThatThrownBy(() -> of(name, ParameterType.BOOLEAN, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("실행 목적이 비어 있으면 거부한다")
    void requiresAtLeastOneExecutionScope() {
        assertThatThrownBy(
                        () ->
                                new ParameterDefinition(
                                        "enabled",
                                        "표시 이름",
                                        "설명",
                                        ParameterType.BOOLEAN,
                                        false,
                                        null,
                                        null,
                                        Set.of(),
                                        null,
                                        null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("실행 목적");
    }

    @Test
    @DisplayName("실행 목적이 다르면 적용 여부가 갈린다")
    void appliesToChecksScope() {
        ParameterDefinition backtestOnly =
                new ParameterDefinition(
                        "slippageRate",
                        "슬리피지",
                        "과거 검증에서만 쓰인다.",
                        ParameterType.DECIMAL,
                        true,
                        null,
                        "ratio",
                        Set.of(ExecutionScope.BACKTEST),
                        NumericRange.closed(BigDecimal.ZERO, BigDecimal.ONE),
                        null);

        assertThat(backtestOnly.appliesTo(ExecutionScope.BACKTEST)).isTrue();
        assertThat(backtestOnly.appliesTo(ExecutionScope.LIVE)).isFalse();
    }

    @Test
    @DisplayName("선택 파라미터에 기본값이 없으면 거부한다")
    void optionalParameterRequiresDefaultValue() {
        assertThatThrownBy(
                        () ->
                                new ParameterDefinition(
                                        "mode",
                                        "표시 이름",
                                        "설명",
                                        ParameterType.ENUM,
                                        false,
                                        null,
                                        null,
                                        BOTH,
                                        null,
                                        List.of(new EnumOption("A", "가"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("선택 파라미터는 기본값");
    }

    @Test
    @DisplayName("필수 파라미터는 기본값 없이도 값이 확정된다")
    void requiredParameterNeedsNoDefault() {
        ParameterDefinition required =
                of("mode", ParameterType.ENUM, null, null, List.of(new EnumOption("A", "가")));

        assertThat(required.required()).isTrue();
        assertThat(required.hasDefault()).isFalse();
    }

    @Test
    @DisplayName("파라미터 정의는 불변이다")
    void isImmutable() {
        ParameterDefinition parameter =
                of("mode", ParameterType.ENUM, null, null, List.of(new EnumOption("A", "가")));

        assertThatThrownBy(() -> parameter.options().add(new EnumOption("B", "나")))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> parameter.executionScopes().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
