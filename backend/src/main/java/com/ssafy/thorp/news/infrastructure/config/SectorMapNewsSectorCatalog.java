package com.ssafy.thorp.news.infrastructure.config;

import com.ssafy.thorp.news.application.port.NewsSectorCatalog;
import com.ssafy.thorp.sector.application.repository.SectorRepository;
import com.ssafy.thorp.sector.domain.model.Sector;
import com.ssafy.thorp.sector.domain.vo.SectorCode;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * {@link NewsSectorCatalog}를 spec 001의 섹터-ETF 대응표로 채운다(spec FR-005).
 *
 * <p>001이 현재 열어 둔 대응표 버전의 섹터 코드가 곧 등록된 식별자다. 열린 버전이 없으면 등록된 섹터가 없다는 뜻이며, 이것은 오류가 아니라 아직 발행 전이라는
 * 상태다. 001은 이 경우 자기 API에서 409를 내지만 뉴스는 그렇게 하지 않는다 — 뉴스 화면은 섹터 대응표와 무관하게 떠야 하고, 섹터 지정 조회만 빈 화면이 된다.
 *
 * <p>매 호출마다 001을 읽는다. 대응표는 파이프라인이 버전을 여닫을 때만 바뀌므로 캐시할 수도 있지만, 닫힌 버전을 들고 있다가 없는 섹터를 통과시키는 쪽이 매번 읽는
 * 비용보다 나쁘다. 조회당 한 번이고 행 수도 섹터 개수만큼이다.
 */
@Component
public class SectorMapNewsSectorCatalog implements NewsSectorCatalog {

    private final SectorRepository sectors;

    public SectorMapNewsSectorCatalog(SectorRepository sectors) {
        this.sectors = sectors;
    }

    @Override
    public boolean isRegistered(String sectorId) {
        return sectorId != null && registeredSectorIds().contains(sectorId);
    }

    @Override
    public Set<String> registeredSectorIds() {
        return sectors.findOpenSectorMap()
                .map(
                        map ->
                                map.sectors().stream()
                                        .map(Sector::code)
                                        .map(SectorCode::value)
                                        .collect(Collectors.toUnmodifiableSet()))
                .orElseGet(Set::of);
    }
}
