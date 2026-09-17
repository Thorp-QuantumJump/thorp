package com.ssafy.thorp.result.application.result;

import com.ssafy.thorp.backtest.domain.model.ResultSource;
import com.ssafy.thorp.backtest.domain.model.RunStatus;

/**
 * {@code POST /results/{publicToken}/retry}의 유스케이스 출력. 004 재시도 결과의 식별자를 모두 공개 토큰으로 바꾼 것이다.
 *
 * @param publicToken 새 요청의 토큰. 원본과 다르다
 * @param retryOfToken 원 요청의 토큰. 004 응답의 {@code retryOf}(원 요청의 {@code backtestId})를 대신한다
 */
public record PublicResultRetry(
        String publicToken, RunStatus status, ResultSource resultSource, String retryOfToken) {}
