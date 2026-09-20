package com.ssafy.thorp.regimelink.domain.model;

import com.ssafy.thorp.regimelink.domain.vo.FilterKey;
import java.time.Instant;
import java.util.Objects;

/**
 * 저장된 가용성 필터 한 행 (data-model §2).
 *
 * <p>키가 같으면 같은 행을 재사용한다 — 같은 비교 묶음·같은 자료에서 필터가 두 번 만들어지지 않는다.
 */
public record LinkFilter(
        Long id,
        FilterKey key,
        FilterDefinition definition,
        AvailableRange availableRange,
        Instant createdAt) {

    public LinkFilter {
        Objects.requireNonNull(key, "filterKey must not be null");
        Objects.requireNonNull(definition, "definition must not be null");
        Objects.requireNonNull(availableRange, "availableRange must not be null");
    }

    /** 저장 전의 새 필터. id·createdAt 은 저장소가 채운다. */
    public static LinkFilter create(
            FilterKey key, FilterDefinition definition, AvailableRange availableRange) {
        return new LinkFilter(null, key, definition, availableRange, null);
    }
}
