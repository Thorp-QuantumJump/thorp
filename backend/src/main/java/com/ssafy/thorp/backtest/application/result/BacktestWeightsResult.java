package com.ssafy.thorp.backtest.application.result;

import com.ssafy.thorp.backtest.application.port.message.EngineOutput;
import com.ssafy.thorp.backtest.application.port.message.WeightsSource;
import java.util.List;

/**
 * {@code GET /backtests/{backtestId}/weights}의 유스케이스 출력(FR-026). 리밸런싱마다 목표·실제 비중.
 *
 * @param partial 취소·한도로 끊긴 부분 결과인가. 먼저 취소한 요청은 자기 스냅샷까지만 담긴다(R-04)
 * @param weightsSource null = 엔진이 기록하지 않았다
 */
public record BacktestWeightsResult(
        String backtestId,
        boolean partial,
        WeightsSource weightsSource,
        List<EngineOutput.WeightsPoint> points) {}
