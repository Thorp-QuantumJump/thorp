package com.ssafy.thorp.regimelink.support;

import com.ssafy.thorp.regimelink.application.repository.RegimeLinkResultRepository;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/** 결과 저장소의 시험용 구현. 저장된 그대로 돌려준다 — 표기를 다시 만들지 않는다는 규칙을 시험도 지킨다. */
public class InMemoryResultRepository implements RegimeLinkResultRepository {

    private final Map<Long, StoredResult> stored = new LinkedHashMap<>();

    @Override
    public Optional<StoredResult> findByLinkId(Long linkId) {
        return Optional.ofNullable(stored.get(linkId));
    }

    @Override
    public void save(Long linkId, StoredResult result) {
        stored.put(linkId, result);
    }

    public int size() {
        return stored.size();
    }
}
