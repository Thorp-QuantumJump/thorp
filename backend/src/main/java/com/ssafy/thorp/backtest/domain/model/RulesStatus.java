package com.ssafy.thorp.backtest.domain.model;

/**
 * 전략 규칙 등록 상태(spec 004 research R-08, T029).
 *
 * <p>팀이 계산 규칙을 확정하지 않은 전략은 명세 없이 {@link #PENDING_DECISION} 행만 둔다. 규칙을 임의로 정해 실행하면 나중에 확정값과 결과 버전이
 * 갈리므로, 접수 단계에서 거부하는 편이 낫다(constitution "실행 전 결정 임의 확정 금지").
 *
 * <p>확정된 규칙을 바꿀 때는 행을 고치지 않고 새 버전 행을 더한 뒤 옛 행을 {@link #SUPERSEDED}로 둔다. 옛 행을 지우거나 고치면 그 버전으로 끝난 실행을
 * 같은 규칙으로 다시 시도할 수 없다.
 */
public enum RulesStatus {
    /** 명세가 있고 새 요청이 고를 수 있는 현재 규칙이다. */
    REGISTERED,
    /** 팀 결정 대기. 명세가 없고 접수하면 422다. */
    PENDING_DECISION,
    /**
     * 새 버전으로 대체된 옛 규칙. 명세는 그대로 남는다.
     *
     * <p>새 요청은 받지 않고(409 {@code REGISTRY_VERSION_CHANGED}) 이미 접수된 실행의 발행·재시도만 이 명세로 계산한다.
     */
    SUPERSEDED
}
