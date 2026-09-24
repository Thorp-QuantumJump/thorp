package com.ssafy.thorp.news.infrastructure.config;

import com.ssafy.thorp.news.application.port.NewsSectorCatalog;
import com.ssafy.thorp.sector.application.repository.SectorRepository;
import com.ssafy.thorp.sector.domain.model.Sector;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * {@link NewsSectorCatalog}를 spec 001의 섹터-ETF 대응표로 채운다(spec FR-005).
 *
 * <p>001이 현재 열어 둔 대응표 버전의 섹터 코드와, 뉴스 등록표가 선언한 뉴스 전용 섹터({@code NEWS_})가 등록된 식별자다(Session
 * 2026-09-25). 001이 현재 열어 둔 대응표 버전의 섹터 코드가 곧 투자 대상 식별자다. 열린 버전이 없으면 등록된 섹터가 없다는 뜻이며, 이것은 오류가 아니라 아직
 * 발행 전이라는 상태다. 001은 이 경우 자기 API에서 409를 내지만 뉴스는 그렇게 하지 않는다 — 뉴스 화면은 섹터 대응표와 무관하게 떠야 하고, 섹터 지정 조회만 빈
 * 화면이 된다.
 *
 * <p>매 호출마다 001을 읽는다. 대응표는 파이프라인이 버전을 여닫을 때만 바뀌므로 캐시할 수도 있지만, 닫힌 버전을 들고 있다가 없는 섹터를 통과시키는 쪽이 매번 읽는
 * 비용보다 나쁘다. 조회당 한 번이고 행 수도 섹터 개수만큼이다.
 */
@Component
public class SectorMapNewsSectorCatalog implements NewsSectorCatalog {

    private final SectorRepository sectors;
    private final NewsLinkRegistry registry;

    public SectorMapNewsSectorCatalog(SectorRepository sectors, NewsLinkRegistry registry) {
        this.sectors = sectors;
        this.registry = registry;
    }

    @Override
    public boolean isRegistered(String sectorId) {
        return sectorId != null && registeredSectorIds().contains(sectorId);
    }

    /** 001에 열린 섹터와 뉴스 전용 섹터를 합친 것. 뉴스 전용 섹터는 001 대응표와 무관하게 늘 유효하다. */
    @Override
    public Set<String> registeredSectorIds() {
        Set<String> ids = new java.util.HashSet<>(openSectorNames().keySet());
        ids.addAll(registry.newsOwnedSectorIds());
        return Set.copyOf(ids);
    }

    /** 빈 화면과 잘못된 요청을 가르는 기준은 001 대응표다. 뉴스 전용 섹터가 있다고 001이 발행된 것은 아니다. */
    @Override
    public boolean isEmpty() {
        return openSectorNames().isEmpty();
    }

    @Override
    public List<SelectableSector> selectableSectors() {
        Map<String, String> open = openSectorNames();
        List<SelectableSector> result = new ArrayList<>();
        for (NewsLinkRegistry.NewsSector sector : registry.newsSectors()) {
            if (sector.owner() == NewsLinkRegistry.SectorOwner.SECTOR_MAP) {
                String name = open.get(sector.sectorId());
                if (name != null) {
                    result.add(new SelectableSector(sector.sectorId(), name, true));
                }
            } else {
                result.add(new SelectableSector(sector.sectorId(), sector.displayName(), false));
            }
        }
        return List.copyOf(result);
    }

    private Map<String, String> openSectorNames() {
        return sectors.findOpenSectorMap()
                .map(
                        map ->
                                map.sectors().stream()
                                        .collect(
                                                Collectors.toMap(
                                                        s -> s.code().value(),
                                                        Sector::name,
                                                        (a, b) -> a,
                                                        LinkedHashMap::new)))
                .map(m -> (Map<String, String>) m)
                .orElseGet(Map::of);
    }
}
