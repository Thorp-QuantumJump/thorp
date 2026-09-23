package com.ssafy.thorp.backtest.presentation.response;

import com.ssafy.thorp.backtest.application.result.BacktestJobResult;
import com.ssafy.thorp.backtest.domain.model.ResultSource;
import com.ssafy.thorp.backtest.domain.model.RunStatus;

/**
 * {@code POST /backtests/{backtestId}/retry} 202 응답(spec 004 T010).
 *
 * <p>접수 202({@link BacktestJobResponse})와 나눠 둔다 — 재시도에만 있는 {@code retryOf} 때문에 접수 응답에 영원히 null 인 칸을
 * 만들면 그 계약이 바뀐다.
 *
 * @param backtestId 새로 만들어진 <b>요청</b>의 식별자. 원본과 다르다
 * @param resultSource 재시도도 동일 버전 판정을 받는다 — 그 사이 같은 조건이 돌고 있으면 {@code JOINED} 다
 * @param retryOf 원본 요청의 식별자
 * @param publicToken 새 요청의 결과 공개 토큰(spec 005). 원본의 토큰과 다르다
 */
public record BacktestRetryResponse(
        String backtestId,
        RunStatus status,
        ResultSource resultSource,
        String retryOf,
        String publicToken) {

    public static BacktestRetryResponse from(BacktestJobResult result) {
        return new BacktestRetryResponse(
                result.backtestId(),
                result.status(),
                result.resultSource(),
                result.retryOf(),
                result.publicToken());
    }
}
