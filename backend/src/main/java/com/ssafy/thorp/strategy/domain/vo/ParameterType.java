package com.ssafy.thorp.strategy.domain.vo;

/**
 * 전략 파라미터가 가질 수 있는 값의 종류. 닫힌 집합이며 정의는 이 밖의 타입을 쓸 수 없다(FR-007).
 *
 * <p>타입은 그 파라미터가 유효 범위를 어떤 형태로 표현하는지도 함께 정한다. 수치형은 {@link NumericRange}로, 열거형은 허용 값 목록으로 범위를 표현하며
 * 논리형은 둘 중 하나라 별도 표현이 없다. 이 대응이 전략마다 다른 파라미터를 하나의 규격으로 담을 수 있게 하는 축이다.
 */
public enum ParameterType {
    /** 정수. bar 개수처럼 셀 수 있는 값에 쓴다. */
    INTEGER,

    /** 십진 실수. 비율·임계값처럼 소수가 필요한 값에 쓴다. */
    DECIMAL,

    /** 참/거짓. */
    BOOLEAN,

    /** 미리 정한 문자열 목록 중 하나. */
    ENUM;

    /** 이 타입이 {@link NumericRange}로 유효 범위를 표현하는지. */
    public boolean usesNumericRange() {
        return this == INTEGER || this == DECIMAL;
    }

    /** 이 타입이 허용 값 목록으로 유효 범위를 표현하는지. */
    public boolean usesEnumValues() {
        return this == ENUM;
    }

    /** 최소 이력 규칙의 대상이 될 수 있는 타입인지. bar 개수는 정수여야 하므로 {@code INTEGER}만 허용한다. */
    public boolean canExpressBarCount() {
        return this == INTEGER;
    }
}
