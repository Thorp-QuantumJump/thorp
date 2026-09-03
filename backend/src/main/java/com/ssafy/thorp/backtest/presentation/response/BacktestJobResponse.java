package com.ssafy.thorp.backtest.presentation.response;

import com.ssafy.thorp.backtest.application.result.BacktestJobResult;
import com.ssafy.thorp.backtest.domain.model.BacktestStatus;

/**
 * 백테스트 Job 응답. 비동기 계약 {@code { backtestId, status }}에 {@code config}와 {@code summary}를 더한 형태다.
 *
 * <p>{@code summary}는 COMPLETED이고 결과가 저장되어 있을 때만 값이 있고, 그 외(QUEUED·RUNNING·FAILED, POST 직후)에는
 * {@code null}이다. 키는 항상 존재한다.
 */
public record BacktestJobResponse(
        Long backtestId,
        BacktestStatus status,
        BacktestConfigResponse config,
        BacktestSummaryResponse summary) {

    public static BacktestJobResponse from(BacktestJobResult result) {
        return new BacktestJobResponse(
                result.id(),
                result.status(),
                BacktestConfigResponse.from(result.config()),
                BacktestSummaryResponse.from(result.summary()));
    }
}
