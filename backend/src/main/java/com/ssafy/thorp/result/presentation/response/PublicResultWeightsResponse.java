package com.ssafy.thorp.result.presentation.response;

import com.ssafy.thorp.backtest.application.port.message.WeightsSource;
import com.ssafy.thorp.backtest.application.result.BacktestWeightsResult;
import com.ssafy.thorp.backtest.presentation.response.BacktestWeightsResponse;
import java.util.List;

/**
 * {@code GET /results/{publicToken}/weights} 응답. 004 응답({@link BacktestWeightsResponse})에서 {@code
 * backtestId}만 버린다.
 */
public record PublicResultWeightsResponse(
        boolean partial, WeightsSource weightsSource, List<BacktestWeightsResponse.Point> points) {

    public static PublicResultWeightsResponse from(BacktestWeightsResult result) {
        BacktestWeightsResponse source = BacktestWeightsResponse.from(result);
        return new PublicResultWeightsResponse(
                source.partial(), source.weightsSource(), source.points());
    }
}
