package com.ssafy.thorp.paramsearch.application.port;

/**
 * 유효성 판정에 필요한 묶음 맥락(contracts/param-search-internal.md §1).
 *
 * <p>등록표의 규칙이 조합만으로 판정할 수 없는 제약을 가질 수 있어 묶음의 맥락을 함께 넘긴다. <b>어떤 제약이 이 값을 쓰는지는 등록표가 정하고 007은 알지
 * 못한다</b>(FR-006·FR-025).
 *
 * @param universeSize 이 묶음이 고정한 섹터 후보 집합의 크기
 */
public record SearchContext(int universeSize) {

    public SearchContext {
        if (universeSize < 0) {
            throw new IllegalArgumentException(
                    "universeSize must not be negative: " + universeSize);
        }
    }
}
