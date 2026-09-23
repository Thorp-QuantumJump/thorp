package com.ssafy.thorp.paramsearch.domain.model;

/**
 * 묶음이 끝까지 가지 못한 이유. data-model.md §2의 {@code stop_reason}이다.
 *
 * <p>기록만 하는 값이 아니라 화면의 부분 결과 안내 문장의 근거다(FR-009). 어느 한도에 걸렸는지 구분해야 사용자가 범위를 좁힐지 기다릴지 판단할 수 있다.
 *
 * <p>값을 채우는 로직은 접수 거부(FR-005b)와 한도 판정이 담당한다. 이 enum은 그 값의 집합만 정한다.
 */
public enum StopReason {
    /** 사용자가 취소했다(FR-009). */
    CANCELLED,
    /** 유효 후보 수가 상한을 넘어 접수를 거부했다(FR-005b). */
    CANDIDATE_LIMIT,
    /** 실행시간 상한에 도달해 발행을 멈췄다. */
    TIME_LIMIT,
    /** 연속 실패 한도에 도달해 발행을 멈췄다. */
    FAILURE_LIMIT,
    /** 유효한 조합이 하나도 없어 실행하지 않았다(Edge Case). */
    NO_VALID_CANDIDATE,
    /** 003 등록표의 활성 전략이 아니어서 조합 생성 전에 거부했다(FR-026). */
    STRATEGY_NOT_REGISTERED,
    /** 004가 실행을 지원하지 않는 전략이어서 조합 생성 전에 거부했다(FR-026). */
    STRATEGY_NOT_EXECUTABLE,
    /**
     * 자동 탐색이 실행할 수 없는 전략이어서 조합 생성 전에 거부했다.
     *
     * <p>{@link #STRATEGY_NOT_EXECUTABLE}과 다르다 — 004는 이 전략을 실행할 수 있고 단건 분석은 그대로 된다. 자동 탐색이 못 하는 것은
     * 목표 비중을 만들어 내는 일이다. 사용자가 할 일도 다르다: 기다리는 것이 아니라 분석하기로 실행하면 된다.
     */
    STRATEGY_NOT_SEARCHABLE
}
