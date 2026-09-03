package com.ssafy.thorp.backtest.application.result;

import com.ssafy.thorp.backtest.domain.model.BacktestJob;
import com.ssafy.thorp.backtest.domain.model.BacktestResult;
import com.ssafy.thorp.backtest.domain.model.BacktestStatus;

/**
 * 백테스트 Job 조회·생성 유스케이스 출력. HTTP 응답 DTO와 분리한다.
 *
 * <p>{@code summary}는 Job이 COMPLETED이고 결과가 저장되어 있을 때만 값이 있고, 그 외에는 null이다.
 */
public record BacktestJobResult(
        Long id,
        BacktestStatus status,
        BacktestConfigResult config,
        BacktestSummaryResult summary) {

    /** 결과 없이 Job만 담는다(QUEUED·RUNNING·FAILED, 또는 COMPLETED이지만 결과 행이 아직 없는 경우). */
    public static BacktestJobResult from(BacktestJob job) {
        return new BacktestJobResult(
                job.getId(), job.getStatus(), BacktestConfigResult.from(job), null);
    }

    /** COMPLETED Job과 저장된 결과를 조합한다. */
    public static BacktestJobResult from(BacktestJob job, BacktestResult result) {
        return new BacktestJobResult(
                job.getId(),
                job.getStatus(),
                BacktestConfigResult.from(job),
                BacktestSummaryResult.from(result.getSummary()));
    }
}
