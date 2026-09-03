package com.ssafy.thorp.strategy.domain.vo;

/**
 * 파라미터 사이의 관계를 나타내는 비교 연산자. 닫힌 집합이다(FR-009).
 *
 * <p>개별 파라미터의 유효 범위는 {@link NumericRange}가 담고, 이 연산자는 두 파라미터의 관계만 담는다. 그래서 제약의 양변은 항상 파라미터 이름이며 상수를
 * 쓸 수 없다. 상수와의 비교를 여기서도 표현할 수 있게 하면 같은 규칙을 두 곳에 쓸 수 있게 되어 정의 작성자마다 다른 곳에 적게 된다.
 */
public enum ConstraintOperator {
    /** 좌변이 우변보다 작다. */
    LT,

    /** 좌변이 우변보다 작거나 같다. */
    LTE,

    /** 좌변이 우변보다 크다. */
    GT,

    /** 좌변이 우변보다 크거나 같다. */
    GTE,

    /** 좌변과 우변이 같다. */
    EQ,

    /** 좌변과 우변이 다르다. */
    NE;

    /**
     * 두 값의 비교 결과가 이 연산자를 만족하는지.
     *
     * @param comparison {@code left.compareTo(right)}의 부호
     */
    public boolean holds(int comparison) {
        return switch (this) {
            case LT -> comparison < 0;
            case LTE -> comparison <= 0;
            case GT -> comparison > 0;
            case GTE -> comparison >= 0;
            case EQ -> comparison == 0;
            case NE -> comparison != 0;
        };
    }

    /** 순서 비교가 필요한 연산자인지. {@code EQ}/{@code NE}는 순서 없는 타입에도 쓸 수 있다. */
    public boolean requiresOrdering() {
        return this != EQ && this != NE;
    }
}
