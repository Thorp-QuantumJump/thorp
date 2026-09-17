package com.ssafy.thorp.result.presentation.response;

import com.ssafy.thorp.backtest.application.result.BacktestTimeseriesResult;
import com.ssafy.thorp.backtest.presentation.response.BacktestTimeseriesResponse;
import java.util.List;

/**
 * {@code GET /results/{publicToken}/timeseries} 응답. 004 응답({@link BacktestTimeseriesResponse})에서
 * {@code backtestId}만 버린다. 점의 모양과 직렬화 규칙(소수 문자열, {@code benchmarkReturn} 키 생략)은 004의 것을 그대로 쓴다.
 */
public record PublicResultTimeseriesResponse(
        boolean partial, List<BacktestTimeseriesResponse.Point> points) {

    public static PublicResultTimeseriesResponse from(BacktestTimeseriesResult result) {
        BacktestTimeseriesResponse source = BacktestTimeseriesResponse.from(result);
        return new PublicResultTimeseriesResponse(source.partial(), source.points());
    }
}
