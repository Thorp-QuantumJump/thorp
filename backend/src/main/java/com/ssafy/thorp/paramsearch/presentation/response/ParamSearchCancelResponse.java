package com.ssafy.thorp.paramsearch.presentation.response;

import com.ssafy.thorp.paramsearch.application.result.ParamSearchCancelResult;

/**
 * 취소 응답(contracts/param-search-api.md §5).
 *
 * <p>{@code skipped}와 {@code inFlight}를 함께 주는 이유: 취소는 "즉시 멈춤"이 아니라 "여기까지만"이다. 몇 개를 실제로 멈췄고 몇 개가 끝까지
 * 도는지 알려 주지 않으면, 화면은 취소를 눌렀는데도 결과가 계속 늘어나는 것을 설명할 수 없다.
 */
public record ParamSearchCancelResponse(
        String searchId, String status, int skipped, long inFlight) {

    public static ParamSearchCancelResponse from(ParamSearchCancelResult result) {
        return new ParamSearchCancelResponse(
                result.searchId(), result.status(), result.skipped(), result.inFlight());
    }
}
