package com.ssafy.thorp.regimelink.support;

import com.ssafy.thorp.regimelink.application.repository.RegimeLinkSearchRepository;
import com.ssafy.thorp.regimelink.domain.model.RegimeLinkSearch;
import com.ssafy.thorp.regimelink.domain.vo.LinkSearchId;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 검색 기록 저장소의 시험용 구현.
 *
 * <p>저장할 때 내부 식별자와 {@code RLS-...} 식별자를 붙인다 — 실제 저장소가 시퀀스로 하는 일이다. {@code (ownerUserId,
 * clientRequestId)} 중복은 막지 않는다. 그 규칙은 서비스가 먼저 조회로 지키고, 동시 저장의 경쟁은 스키마 제약이 막으므로 여기서 흉내 내면 시험이 확인하지
 * 않는 경로를 늘릴 뿐이다.
 */
public class InMemorySearchRepository implements RegimeLinkSearchRepository {

    private final Map<Long, RegimeLinkSearch> stored = new LinkedHashMap<>();
    private final AtomicLong sequence = new AtomicLong();

    @Override
    public Optional<RegimeLinkSearch> findBySearchId(LinkSearchId searchId) {
        return stored.values().stream()
                .filter(search -> searchId.equals(search.searchId()))
                .findFirst();
    }

    @Override
    public Optional<RegimeLinkSearch> findByClientRequest(
            Long ownerUserId, String clientRequestId) {
        return stored.values().stream()
                .filter(
                        search ->
                                Objects.equals(search.ownerUserId(), ownerUserId)
                                        && Objects.equals(
                                                search.clientRequestId(), clientRequestId))
                .findFirst();
    }

    @Override
    public RegimeLinkSearch save(RegimeLinkSearch search) {
        long id = sequence.incrementAndGet();
        RegimeLinkSearch saved =
                new RegimeLinkSearch(
                        id,
                        LinkSearchId.of(LocalDate.of(2026, 9, 21), id),
                        search.ownerUserId(),
                        search.entryPath(),
                        search.snapshotId(),
                        search.snapshotKey(),
                        search.mode(),
                        search.fieldId(),
                        search.baselineMonth(),
                        search.baseRunId(),
                        search.compareRunId(),
                        search.baseRequestId(),
                        search.compareRequestId(),
                        search.filterId(),
                        search.shownCases(),
                        search.excludedCount(),
                        search.fullSearchShown(),
                        search.previousSearchId(),
                        search.retention(),
                        search.basis(),
                        search.clientRequestId(),
                        Instant.now());
        stored.put(id, saved);
        return saved;
    }

    public int size() {
        return stored.size();
    }
}
