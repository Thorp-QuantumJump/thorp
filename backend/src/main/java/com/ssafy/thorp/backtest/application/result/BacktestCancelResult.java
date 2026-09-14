package com.ssafy.thorp.backtest.application.result;

import com.ssafy.thorp.backtest.domain.model.RunStatus;

/**
 * {@code POST /backtests/{backtestId}/cancel}의 유스케이스 출력.
 *
 * @param status 요청의 상태. 취소한 요청은 곧바로 CANCELLED 다(파생 상태, data-model.md)
 * @param cancelRequested 워커에 CANCEL 을 보냈고 FINISHED 를 기다리는 중인가. 대기 중 취소·합류 취소는 false
 */
public record BacktestCancelResult(String backtestId, RunStatus status, boolean cancelRequested) {}
