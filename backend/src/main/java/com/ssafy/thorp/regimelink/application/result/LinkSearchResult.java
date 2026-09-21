package com.ssafy.thorp.regimelink.application.result;

import com.ssafy.thorp.regimelink.domain.vo.LinkSearchId;
import java.time.YearMonth;
import java.util.List;

/**
 * 검색 기록 생성 결과 (contracts §1b).
 *
 * <p>§1 판정 응답에 {@code linkSearchId}와 변경 알림만 더한 모양이다. <b>유지율은 여기 없다</b> — 기록 전용이고 담을 자리를 만들지 않는 것으로
 * 막는다(FR-021).
 *
 * @param created 이번 요청이 기록을 새로 만들었는가. 거짓이면 멱등 재시도라 200 으로 나간다
 */
public record LinkSearchResult(
        LinkSearchId searchId,
        AvailabilityResult availability,
        BundleChange bundleChanged,
        boolean created) {

    /**
     * 직전 검색과 견준 비교 묶음 변경 (FR-021).
     *
     * @param excludedCount 이번 목록에서 필터로 빠진 사례 수
     */
    public record BundleChange(List<YearMonth> added, List<YearMonth> removed, int excludedCount) {

        public BundleChange {
            added = added == null ? List.of() : List.copyOf(added);
            removed = removed == null ? List.of() : List.copyOf(removed);
        }
    }
}
