package com.ssafy.thorp.result.presentation.response;

import com.ssafy.thorp.backtest.application.result.BacktestPositionsResult;
import com.ssafy.thorp.backtest.presentation.response.BacktestPositionsResponse;
import java.time.LocalDate;
import java.util.List;

/**
 * {@code GET /results/{publicToken}/positions} 응답. 004 응답({@link BacktestPositionsResponse})에서
 * {@code backtestId}만 버린다.
 */
public record PublicResultPositionsResponse(
        boolean partial,
        LocalDate requestedDate,
        LocalDate snapshotDate,
        List<BacktestPositionsResponse.Position> positions) {

    public static PublicResultPositionsResponse from(BacktestPositionsResult result) {
        BacktestPositionsResponse source = BacktestPositionsResponse.from(result);
        return new PublicResultPositionsResponse(
                source.partial(),
                source.requestedDate(),
                source.snapshotDate(),
                source.positions());
    }
}
