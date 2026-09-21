package com.ssafy.thorp.regimelink.support;

import com.ssafy.thorp.regimelink.application.repository.RegimeLinkUseRepository;
import com.ssafy.thorp.regimelink.domain.model.RegimeLinkUse;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/** 이용 기록 저장소의 시험용 구현. 누가 어느 요청으로 그 계산을 열었는지만 쌓아 둔다. */
public class InMemoryUseRepository implements RegimeLinkUseRepository {

    private final List<RegimeLinkUse> stored = new ArrayList<>();
    private final AtomicLong sequence = new AtomicLong();

    @Override
    public List<RegimeLinkUse> findByLinkSearchId(Long linkSearchId) {
        return stored.stream()
                .filter(use -> Objects.equals(use.linkSearchId(), linkSearchId))
                .toList();
    }

    @Override
    public List<RegimeLinkUse> findByLinkId(Long linkId) {
        return stored.stream().filter(use -> Objects.equals(use.linkId(), linkId)).toList();
    }

    @Override
    public List<RegimeLinkUse> findByOriginRunId(Long originRunId) {
        return stored.stream()
                .filter(use -> Objects.equals(use.originRunId(), originRunId))
                .toList();
    }

    @Override
    public RegimeLinkUse save(RegimeLinkUse use) {
        RegimeLinkUse saved =
                new RegimeLinkUse(
                        sequence.incrementAndGet(),
                        use.linkSearchId(),
                        use.linkId(),
                        use.role(),
                        use.originRunId(),
                        use.originRequestId(),
                        use.createdRequestId(),
                        Instant.now());
        stored.add(saved);
        return saved;
    }

    public List<RegimeLinkUse> all() {
        return List.copyOf(stored);
    }

    public int size() {
        return stored.size();
    }
}
