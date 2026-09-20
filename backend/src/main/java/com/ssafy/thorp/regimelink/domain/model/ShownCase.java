package com.ssafy.thorp.regimelink.domain.model;

import com.ssafy.thorp.regimelink.domain.vo.LinkId;
import java.time.YearMonth;
import java.util.List;
import java.util.Objects;

/**
 * 검색 기록에 남기는 "그때 보인 사례" 한 줄 (data-model §3 {@code shown_cases}).
 *
 * <p>순위는 010이 매긴 원래 값이다 — 필터로 빠진 사례가 있어도 다시 매기지 않는다. {@code linkIds}는 그 사례로 만든 계산이 있을 때만 찬다.
 */
public record ShownCase(
        YearMonth endMonth, int rank, Availability availability, List<LinkId> linkIds) {

    public ShownCase {
        Objects.requireNonNull(endMonth, "endMonth must not be null");
        Objects.requireNonNull(availability, "availability must not be null");
        linkIds = linkIds == null ? List.of() : List.copyOf(linkIds);
    }
}
