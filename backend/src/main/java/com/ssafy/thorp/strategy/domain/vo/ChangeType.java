package com.ssafy.thorp.strategy.domain.vo;

/**
 * 전략 정의 변경의 호환성 등급(FR-019b).
 *
 * <p>등급을 남기는 목적은 저장된 파라미터 값 집합이 아직 유효한지를 사람에게 설명하는 것이다. 값 자체의 유효 여부는 현재 정의에 다시 판정해서 얻으며(FR-019c), 이
 * 등급이 판정을 대신하지 않는다.
 */
public enum ChangeType {
    /** 최초 정의. {@code definitionVersion}이 1인 항목만 가질 수 있다. */
    INITIAL,

    /** 직전 버전에서 유효했던 모든 파라미터 값 집합이 이 버전에서도 유효하다. */
    BACKWARD_COMPATIBLE,

    /** 직전 버전에서 유효했던 값 집합 중 하나 이상이 이 버전에서 무효해진다. */
    INCOMPATIBLE
}
