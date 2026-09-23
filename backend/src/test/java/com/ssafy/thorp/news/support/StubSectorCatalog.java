package com.ssafy.thorp.news.support;

import com.ssafy.thorp.news.application.port.NewsSectorCatalog;
import java.util.Set;

/**
 * 섹터 등록표 대역.
 *
 * <p>비어 있는 상태를 기본으로 둔다. spec 001의 대응표가 아직 발행되지 않은 것이 지금의 실제 상태이고, 그 상태에서 뉴스가 정상 동작하는지가 시험할 값어치가 가장
 * 큰 경우다.
 */
public final class StubSectorCatalog implements NewsSectorCatalog {

    private final Set<String> registered;

    /** 대응표가 아직 발행되지 않은 상태. */
    public StubSectorCatalog() {
        this(Set.of());
    }

    public StubSectorCatalog(Set<String> registered) {
        this.registered = Set.copyOf(registered);
    }

    @Override
    public boolean isRegistered(String sectorId) {
        return registered.contains(sectorId);
    }

    @Override
    public Set<String> registeredSectorIds() {
        return registered;
    }
}
