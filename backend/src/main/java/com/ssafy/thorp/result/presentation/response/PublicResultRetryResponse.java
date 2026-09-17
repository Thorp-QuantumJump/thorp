package com.ssafy.thorp.result.presentation.response;

import com.ssafy.thorp.backtest.domain.model.ResultSource;
import com.ssafy.thorp.backtest.domain.model.RunStatus;
import com.ssafy.thorp.result.application.result.PublicResultRetry;

/**
 * {@code POST /results/{publicToken}/retry} 202 응답. 004 재시도 응답({@code BacktestRetryResponse})의
 * {@code backtestId}·{@code retryOf}를 공개 토큰으로 바꾼 것이다.
 */
public record PublicResultRetryResponse(
        String publicToken, RunStatus status, ResultSource resultSource, String retryOfToken) {

    public static PublicResultRetryResponse from(PublicResultRetry result) {
        return new PublicResultRetryResponse(
                result.publicToken(),
                result.status(),
                result.resultSource(),
                result.retryOfToken());
    }
}
