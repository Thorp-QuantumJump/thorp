package com.ssafy.thorp.backtest.domain.model;

/**
 * 백테스트 Job 상태. 흐름: QUEUED → RUNNING → COMPLETED | FAILED. RUNNING 이후는 워커 이벤트(STARTED/FINISHED)로
 * 갱신된다.
 *
 * <p>전이 규칙은 {@link BacktestJob#markRunning()} 등 도메인 메서드가 지킨다.
 */
public enum BacktestStatus {
    QUEUED,
    RUNNING,
    COMPLETED,
    FAILED;

    /** 더 이상 바뀌지 않는 상태인가. 최종 상태의 Job에 온 이벤트는 무시된다. */
    public boolean isFinal() {
        return this == COMPLETED || this == FAILED;
    }
}
