package com.ssafy.thorp.backtest.domain.model;

/**
 * 백테스트 실행 상태. 흐름: QUEUED → RUNNING → COMPLETED | FAILED | CANCELLED. RUNNING 이후는 워커
 * 이벤트(STARTED/FINISHED)로 갱신된다.
 *
 * <p>{@code CANCELLED}는 사용자 취소·실행 시간 한도로 끊긴 실행이다(spec 004 Clarify Q1). 부분 결과가 붙을 수 있다는 점에서 FAILED와
 * 다르다 — 어디까지 계산했는지를 {@code progressLastTradingDate}가 가리킨다.
 *
 * <p>전이 규칙은 {@link BacktestRun#markRunning(String)} 등 도메인 메서드가 지킨다. 이 enum은 메시지 계약의 {@code
 * application.port.message.RunStatus}와 이름이 같지만 다른 집합이다 — 계약 쪽에는 대기·실행 상태가 없다.
 */
public enum RunStatus {
    QUEUED,
    RUNNING,
    COMPLETED,
    FAILED,
    CANCELLED;

    /** 더 이상 바뀌지 않는 상태인가. 최종 상태의 실행에 온 이벤트는 무시된다. */
    public boolean isFinal() {
        return this == COMPLETED || this == FAILED || this == CANCELLED;
    }
}
