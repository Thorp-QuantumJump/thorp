package com.ssafy.thorp.paramsearch.presentation.response;

import com.ssafy.thorp.paramsearch.application.result.ParamSearchRetryResult;

/** 재시도 응답(contracts/param-search-api.md §5.1). */
public record ParamSearchRetryResponse(
        String searchId, String status, int retried, long remainingFailed) {

    public static ParamSearchRetryResponse from(ParamSearchRetryResult result) {
        return new ParamSearchRetryResponse(
                result.searchId(), result.status(), result.retried(), result.remainingFailed());
    }
}
