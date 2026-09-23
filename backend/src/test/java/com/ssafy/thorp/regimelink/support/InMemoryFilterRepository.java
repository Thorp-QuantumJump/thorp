package com.ssafy.thorp.regimelink.support;

import com.ssafy.thorp.regimelink.application.repository.RegimeLinkFilterRepository;
import com.ssafy.thorp.regimelink.domain.model.LinkFilter;
import com.ssafy.thorp.regimelink.domain.vo.FilterKey;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/** 필터 저장소의 시험용 구현. 키가 같으면 같은 행을 돌려준다는 규칙만 지킨다. */
public class InMemoryFilterRepository implements RegimeLinkFilterRepository {

    private final Map<String, LinkFilter> stored = new LinkedHashMap<>();
    private final AtomicLong sequence = new AtomicLong();

    @Override
    public Optional<LinkFilter> findByKey(FilterKey key) {
        return Optional.ofNullable(stored.get(key.value()));
    }

    @Override
    public LinkFilter save(LinkFilter filter) {
        return stored.computeIfAbsent(
                filter.key().value(),
                key ->
                        new LinkFilter(
                                sequence.incrementAndGet(),
                                filter.key(),
                                filter.definition(),
                                filter.availableRange(),
                                Instant.now()));
    }

    public int size() {
        return stored.size();
    }
}
