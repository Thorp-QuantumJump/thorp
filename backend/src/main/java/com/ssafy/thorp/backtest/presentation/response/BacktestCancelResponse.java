package com.ssafy.thorp.backtest.presentation.response;

import com.ssafy.thorp.backtest.application.result.BacktestCancelResult;
import com.ssafy.thorp.backtest.domain.model.RunStatus;

/**
 * {@code POST /backtests/{backtestId}/cancel} 202 응답(spec 004 contracts/rest-api.md).
 *
 * @param cancelRequested 워커에 CANCEL 을 보내 FINISHED 를 기다리는 중. 취소 반영은 다음 진행 배치 안이다
 */
public record BacktestCancelResponse(String backtestId, RunStatus status, boolean cancelRequested) {

    public static BacktestCancelResponse from(BacktestCancelResult result) {
        return new BacktestCancelResponse(
                result.backtestId(), result.status(), result.cancelRequested());
    }
}
