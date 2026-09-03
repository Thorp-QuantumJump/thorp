package com.ssafy.thorp.strategy.domain.vo;

/**
 * 파라미터 값 판정에서 나올 수 있는 위반의 종류. 닫힌 집합이다(FR-011).
 *
 * <p>코드를 닫아 두면 소비 지점이 위반의 종류에 따라 화면 처리를 달리할 수 있고, 사람이 읽는 문구가 바뀌어도 처리 로직이 흔들리지 않는다. 판정 자체는 {@code
 * domain.policy}의 판정기가 수행하며 이 enum은 그 결과의 어휘만 정한다.
 */
public enum ViolationCode {
    /** 카탈로그에 없는 전략을 지정했다. */
    UNKNOWN_STRATEGY,

    /** 정의에 존재하지 않는 파라미터 이름이 입력에 포함됐다. */
    UNKNOWN_PARAMETER,

    /** 필수 파라미터의 값이 없다. */
    REQUIRED_PARAMETER_MISSING,

    /** 값의 타입이 파라미터 정의의 타입과 다르다. */
    TYPE_MISMATCH,

    /** 값이 유효 범위의 경계를 벗어났다. */
    OUT_OF_RANGE,

    /** 값이 허용 간격(step)의 배수가 아니다. */
    STEP_MISMATCH,

    /** 값이 열거형의 허용 목록에 없다. */
    NOT_IN_ENUM,

    /** 파라미터 사이의 제약을 위반했다. */
    CONSTRAINT_VIOLATED
}
