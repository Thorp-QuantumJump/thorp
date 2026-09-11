package com.ssafy.thorp.backtest.application.port.message;

import java.time.Instant;

/**
 * STARTED 이벤트 payload.
 *
 * @param stage 시작 시점 단계. 항상 PREPARING_DATA
 * @param resource 실행 프로세스 식별(pid·hostname). 자동 재시도 진단용
 */
public record StartedPayload(Instant startedAt, ProcessingStage stage, ProcessInfo resource) {

    public record ProcessInfo(Integer pid, String hostname) {}
}
