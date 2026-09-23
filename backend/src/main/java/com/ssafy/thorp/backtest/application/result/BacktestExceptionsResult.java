package com.ssafy.thorp.backtest.application.result;

import com.ssafy.thorp.backtest.application.port.message.EngineOutput;
import java.util.List;

/**
 * {@code GET /backtests/{backtestId}/exceptions}의 유스케이스 출력(FR-020·FR-030). 005가 "결과 옆 한 문장"에 쓰는
 * 원천이다.
 *
 * @param partial 취소·한도로 끊긴 부분 결과인가
 */
public record BacktestExceptionsResult(
        String backtestId, boolean partial, List<EngineOutput.ExceptionRecord> items) {}
