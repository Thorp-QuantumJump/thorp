package com.ssafy.thorp.backtest.application.port.message;

import java.time.Instant;

/**
 * STARTED 이벤트 payload.
 *
 * @param stage 시작 시점 단계. 항상 PREPARING_DATA
 * @param resource 실행 프로세스 식별(pid·hostname). 자동 재시도 진단용
 */
public record StartedPayload(Instant startedAt, ProcessingStage stage, ProcessInfo resource) {

    /**
     * @param queue 이 작업을 집은 큐({@code backtest.jobs} / {@code backtest.jobs.bulk}). 선택(R-20, T012)
     * @param role 집은 워커의 역할({@code single} / {@code flex}). 선택 — 공용 워커가 단건을 지원한 비율은 이 값으로 센다
     */
    public record ProcessInfo(Integer pid, String hostname, String queue, String role) {}
}
