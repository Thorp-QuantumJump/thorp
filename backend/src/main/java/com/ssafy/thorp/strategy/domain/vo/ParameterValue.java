package com.ssafy.thorp.strategy.domain.vo;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.Optional;

/**
 * 파라미터 하나가 가진 값. {@link ParameterType}의 네 종류에 각각 대응하는 봉인된 계층이다.
 *
 * <p>{@code Object}나 {@code Map<String, Object>}로 값을 다루지 않는 이유는 전략마다 파라미터의 타입 구성이 다르더라도 값의 종류가 컴파일
 * 시점에 닫혀 있어야 하기 때문이다. 봉인 계층이면 새 타입을 추가할 때 이 값을 다루는 모든 곳이 컴파일 오류로 드러나므로, 특정 전략만을 위한 예외 경로가 조용히 생기지
 * 않는다.
 *
 * <p>실수는 {@link BigDecimal}로 담는다. 십진 연산으로 판정해야 소비 지점마다 다른 결론이 나오지 않는다(SC-002).
 */
public sealed interface ParameterValue {

    /** 이 값이 대응하는 파라미터 타입. */
    ParameterType type();

    /** 표현 계층으로 내보낼 원시 값. */
    Object raw();

    /** 수치형이면 십진 값, 그 외에는 빈 값. 범위·제약 판정이 타입 분기 없이 수치를 꺼낼 수 있게 한다. */
    default Optional<BigDecimal> asNumber() {
        return Optional.empty();
    }

    static ParameterValue of(long value) {
        return new IntegerValue(value);
    }

    static ParameterValue of(BigDecimal value) {
        return new DecimalValue(value);
    }

    static ParameterValue of(boolean value) {
        return new BooleanValue(value);
    }

    static ParameterValue ofEnum(String value) {
        return new EnumValue(value);
    }

    /** 정수 값. */
    record IntegerValue(long value) implements ParameterValue {

        @Override
        public ParameterType type() {
            return ParameterType.INTEGER;
        }

        @Override
        public Object raw() {
            return value;
        }

        @Override
        public Optional<BigDecimal> asNumber() {
            return Optional.of(BigDecimal.valueOf(value));
        }
    }

    /** 십진 실수 값. */
    record DecimalValue(BigDecimal value) implements ParameterValue {

        public DecimalValue {
            Objects.requireNonNull(value, "DECIMAL 값은 null일 수 없다");
        }

        @Override
        public ParameterType type() {
            return ParameterType.DECIMAL;
        }

        @Override
        public Object raw() {
            return value;
        }

        @Override
        public Optional<BigDecimal> asNumber() {
            return Optional.of(value);
        }
    }

    /** 참/거짓 값. */
    record BooleanValue(boolean value) implements ParameterValue {

        @Override
        public ParameterType type() {
            return ParameterType.BOOLEAN;
        }

        @Override
        public Object raw() {
            return value;
        }
    }

    /** 열거형에서 선택된 값. 허용 목록에 속하는지는 파라미터 정의에 대한 판정에서 확인한다. */
    record EnumValue(String value) implements ParameterValue {

        public EnumValue {
            Objects.requireNonNull(value, "ENUM 값은 null일 수 없다");
            if (value.isBlank()) {
                throw new IllegalArgumentException("ENUM 값은 비어 있을 수 없다");
            }
        }

        @Override
        public ParameterType type() {
            return ParameterType.ENUM;
        }

        @Override
        public Object raw() {
            return value;
        }
    }
}
