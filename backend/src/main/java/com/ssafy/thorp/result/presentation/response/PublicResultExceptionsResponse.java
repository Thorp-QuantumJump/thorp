package com.ssafy.thorp.result.presentation.response;

import com.ssafy.thorp.backtest.application.result.BacktestExceptionsResult;
import com.ssafy.thorp.backtest.presentation.response.BacktestExceptionsResponse;
import java.util.List;

/**
 * {@code GET /results/{publicToken}/exceptions} 응답. 004 응답({@link BacktestExceptionsResponse})에서
 * {@code backtestId}만 버린다.
 */
public record PublicResultExceptionsResponse(
        boolean partial, List<BacktestExceptionsResponse.Item> items) {

    public static PublicResultExceptionsResponse from(BacktestExceptionsResult result) {
        BacktestExceptionsResponse source = BacktestExceptionsResponse.from(result);
        return new PublicResultExceptionsResponse(source.partial(), source.items());
    }
}
