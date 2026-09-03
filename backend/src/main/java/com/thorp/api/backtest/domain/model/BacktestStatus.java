package com.thorp.api.backtest.domain.model;

/** 백테스트 Job 상태. 흐름: QUEUED → RUNNING → COMPLETED | FAILED (RUNNING 이후는 Worker가 갱신). */
public enum BacktestStatus {
    QUEUED,
    RUNNING,
    COMPLETED,
    FAILED
}
