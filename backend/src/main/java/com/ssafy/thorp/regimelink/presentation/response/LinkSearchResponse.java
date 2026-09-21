package com.ssafy.thorp.regimelink.presentation.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.ssafy.thorp.regimelink.application.result.LinkSearchResult;
import java.time.YearMonth;
import java.util.List;

/**
 * 검색 기록 생성 응답 (contracts §1b).
 *
 * <p>§1 판정 응답에 {@code linkSearchId}와 변경 알림만 더한 모양이다 — 화면이 두 경로에서 같은 파서를 쓴다.
 *
 * <p><b>유지율은 싣지 않는다</b>(FR-021). 담을 자리를 만들지 않는 것으로 막는다.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record LinkSearchResponse(
        AvailabilityResponse.Basis basis,
        String linkSearchId,
        AvailabilityResponse.Search search,
        AvailabilityResponse.Bundle bundle,
        AvailabilityResponse.Filter filter,
        List<AvailabilityResponse.Case> cases,
        BundleChanged bundleChanged) {

    /** 직전 목록과 달라진 사례. 변경이 없으면 이 키가 통째로 없다. */
    public record BundleChanged(List<String> added, List<String> removed, int excludedCount) {}

    public static LinkSearchResponse from(LinkSearchResult result) {
        AvailabilityResponse availability = AvailabilityResponse.from(result.availability());
        return new LinkSearchResponse(
                availability.basis(),
                result.searchId() == null ? null : result.searchId().value(),
                availability.search(),
                availability.bundle(),
                availability.filter(),
                availability.cases(),
                changed(result.bundleChanged()));
    }

    private static BundleChanged changed(LinkSearchResult.BundleChange change) {
        if (change == null) {
            return null;
        }
        return new BundleChanged(
                change.added().stream().map(YearMonth::toString).toList(),
                change.removed().stream().map(YearMonth::toString).toList(),
                change.excludedCount());
    }
}
