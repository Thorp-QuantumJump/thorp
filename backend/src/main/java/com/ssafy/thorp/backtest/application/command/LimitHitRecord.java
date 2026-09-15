package com.ssafy.thorp.backtest.application.command;

import com.ssafy.thorp.backtest.domain.model.ExecutionType;
import java.time.Instant;

/**
 * 한도 도달 한 건({@code backtest_limit_hit} 한 행, FR-006). 접수 거절은 실행 행이 없고 실행 정지는 아직 끝나지 않아 측정 기록과 표를
 * 나눴다(R-10 ②).
 *
 * @param kind {@link Kind}
 * @param reason 접수 거절은 {@code PER_USER}·{@code TOTAL}, 정지는 {@code EXECUTION_LIMIT}
 * @param requesterId 거절에만 있다
 * @param runId 정지에만 있다
 */
public record LimitHitRecord(
        Kind kind,
        ExecutionType executionType,
        String reason,
        Long requesterId,
        Long runId,
        Instant at) {

    public enum Kind {
        /** 접수가 429 로 거절했다. */
        ADMISSION_REJECTED,
        /** 실행 시간 상한을 넘겨 CANCEL 을 보냈다. */
        RUN_STOPPED
    }
}
