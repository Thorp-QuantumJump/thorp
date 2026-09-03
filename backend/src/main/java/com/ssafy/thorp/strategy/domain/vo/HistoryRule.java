package com.ssafy.thorp.strategy.domain.vo;

/**
 * 최소 이력 요구량을 산출하는 규칙의 종류. 닫힌 집합이며 자유 수식은 허용하지 않는다(FR-010).
 *
 * <p>규칙을 닫아 두는 이유는 정의만으로 요구량을 산출할 수 있어야 하기 때문이다. 자유 수식을 허용하면 그것을 해석하는 코드가 소비 지점마다 생기고, 같은 정의에서 서로
 * 다른 요구량이 나올 수 있다(FR-010b).
 */
public enum HistoryRule {
    /** 파라미터와 무관한 고정 상수. */
    FIXED,

    /** 지정한 파라미터 하나의 값. */
    PARAMETER,

    /** 지정한 파라미터들의 값 중 최댓값. */
    MAX_PARAMETERS,

    /** 지정한 파라미터들의 값의 합. */
    SUM_PARAMETERS;

    /** 고정 상수를 필요로 하는 규칙인지. */
    public boolean usesFixedValue() {
        return this == FIXED;
    }

    /** 대상 파라미터 목록을 필요로 하는 규칙인지. */
    public boolean usesTargetParameters() {
        return this != FIXED;
    }

    /** 대상 파라미터 개수의 상한. 상한이 없으면 {@link Integer#MAX_VALUE}. */
    public int maxTargetCount() {
        return this == PARAMETER ? 1 : Integer.MAX_VALUE;
    }
}
