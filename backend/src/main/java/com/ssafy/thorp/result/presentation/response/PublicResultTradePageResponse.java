package com.ssafy.thorp.result.presentation.response;

import com.ssafy.thorp.backtest.application.result.BacktestTradePageResult;
import com.ssafy.thorp.backtest.presentation.response.BacktestTradePageResponse;
import java.util.List;

/**
 * {@code GET /results/{publicToken}/trades} 응답. 004 응답({@link BacktestTradePageResponse})에서 {@code
 * backtestId}만 버린다. 거래일 변환(거래소 현지)과 OPEN 거래의 명시적 null은 004의 것을 그대로 쓴다.
 */
public record PublicResultTradePageResponse(
        boolean partial,
        int page,
        int size,
        long totalElements,
        int totalPages,
        List<BacktestTradePageResponse.Trade> content) {

    public static PublicResultTradePageResponse from(BacktestTradePageResult result) {
        BacktestTradePageResponse source = BacktestTradePageResponse.from(result);
        return new PublicResultTradePageResponse(
                source.partial(),
                source.page(),
                source.size(),
                source.totalElements(),
                source.totalPages(),
                source.content());
    }
}
