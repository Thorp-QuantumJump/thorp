package com.ssafy.thorp.backtest.domain.model;

import java.time.Instant;
import java.util.Objects;

/**
 * 같은 조건의 요청이 새 계산 없이 끝난 기록(spec 004 FR-008, T010).
 *
 * <p>접수가 진행 중인 실행에 합류시키거나({@code JOINED}) 완료된 실행의 결과를 재사용하면({@code REUSED}) 한 줄 남긴다. 원가·운영 지표에서 새로
 * 계산한 실행과 아낀 요청을 가르는 근거다.
 *
 * <p><b>요청의 {@code resultSource}로 대신하지 않는다.</b> 그것은 요청의 현재 상태라 나중에 덮일 수 있고, 이 기록은 접수 시점에 무슨 판단이
 * 있었는지에 대한 사실이다. 그래서 추가만 하고 고치지 않는다.
 *
 * @param kind {@code JOINED} 또는 {@code REUSED}. {@code NEW}는 이 기록의 대상이 아니다 — 새 계산은 실행 행 자체가 기록이다
 * @param requestExecutionType 접수 시점 요청의 종류. 요청 행과 조인하지 않고도 종류별로 셀 수 있게 복사해 둔다
 */
public record BacktestReuseEvent(
        Long id,
        Long runId,
        Long requestId,
        ResultSource kind,
        ExecutionType requestExecutionType,
        Instant at) {

    public BacktestReuseEvent {
        Objects.requireNonNull(runId, "runId must not be null");
        Objects.requireNonNull(requestId, "requestId must not be null");
        Objects.requireNonNull(kind, "kind must not be null");
        Objects.requireNonNull(requestExecutionType, "requestExecutionType must not be null");
        Objects.requireNonNull(at, "at must not be null");
        if (kind == ResultSource.NEW) {
            throw new IllegalArgumentException(
                    "NEW is not a reuse: runId=" + runId + ", requestId=" + requestId);
        }
    }

    /** 새 기록. id는 저장 전이라 null이다. */
    public static BacktestReuseEvent of(
            Long runId,
            Long requestId,
            ResultSource kind,
            ExecutionType requestExecutionType,
            Instant at) {
        return new BacktestReuseEvent(null, runId, requestId, kind, requestExecutionType, at);
    }
}
