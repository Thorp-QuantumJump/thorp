package com.ssafy.thorp.backtest.domain.model;

/**
 * 전략 규칙 등록 상태(spec 004 research R-08).
 *
 * <p>팀이 계산 규칙을 확정하지 않은 전략은 명세 없이 {@link #PENDING_DECISION} 행만 둔다. 규칙을 임의로 정해 실행하면 나중에 확정값과 결과 버전이
 * 갈리므로, 접수 단계에서 거부하는 편이 낫다(constitution "실행 전 결정 임의 확정 금지").
 */
public enum RulesStatus {
    /** 명세가 있어 실행할 수 있다. */
    REGISTERED,
    /** 팀 결정 대기. 명세가 없고 접수하면 422다. */
    PENDING_DECISION
}
