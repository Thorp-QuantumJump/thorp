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
    private final boolean sectorMapPublished;

    /** 대응표가 아직 발행되지 않은 상태. */
    public StubSectorCatalog() {
        this(Set.of());
    }

    public StubSectorCatalog(Set<String> registered) {
        this(registered, !registered.isEmpty());
    }

    private StubSectorCatalog(Set<String> registered, boolean sectorMapPublished) {
        this.registered = Set.copyOf(registered);
        this.sectorMapPublished = sectorMapPublished;
    }

    /** 001 대응표는 아직 없고 012의 뉴스 전용 섹터만 등록된 상태. 실제 운영이 001 발행 전에 이 모양이다. */
    public static StubSectorCatalog newsOwnedOnly(Set<String> newsSectors) {
        return new StubSectorCatalog(newsSectors, false);
    }

    @Override
    public boolean isRegistered(String sectorId) {
        return registered.contains(sectorId);
    }

    @Override
    public Set<String> registeredSectorIds() {
        return registered;
    }

    @Override
    public boolean isEmpty() {
        return !sectorMapPublished;
    }
}
