package com.ssafy.thorp.learning.infrastructure.sector;

import com.ssafy.thorp.learning.application.port.SectorMapPort;
import com.ssafy.thorp.sector.application.exception.SectorMapNotReadyException;
import com.ssafy.thorp.sector.application.result.SectorListResult;
import com.ssafy.thorp.sector.application.service.SectorService;
import com.ssafy.thorp.sector.domain.model.Sector;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * 001 {@link SectorService#getSectors()}로 {@link SectorMapPort}를 채운다. 004 {@code
 * MarketMetadataPort}와 같은 방식이다(research R8).
 *
 * <p>의존 방향은 {@code learning → sector} 하나다. {@code SectorListResult}가 {@code Sector} 도메인 모델을 노출하므로 그
 * 타입까지 읽는다. {@code sector}가 {@code learning}을 참조하면 {@code ArchitectureTest} 규칙 10이 빌드를 막는다.
 *
 * <p>{@link SectorMapNotReadyException}만 {@link Optional#empty()}로 바꾼다. DB 장애 같은 다른 실패는 그대로 올려 보낸다.
 * 그것까지 "대응표 없음"으로 뭉개면 장애가 '예시로 진입할 수 없음' 안내로 숨는다.
 */
@Component
public class SectorServiceSectorMapAdapter implements SectorMapPort {

    private final SectorService sectorService;

    public SectorServiceSectorMapAdapter(SectorService sectorService) {
        this.sectorService = sectorService;
    }

    @Override
    public Optional<SectorMapView> currentMap() {
        SectorListResult map;
        try {
            map = sectorService.getSectors();
        } catch (SectorMapNotReadyException notReady) {
            return Optional.empty();
        }
        Map<String, String> names = new LinkedHashMap<>();
        for (Sector sector : map.sectors()) {
            names.put(sector.code().value(), sector.name());
        }
        return Optional.of(new SectorMapView(map.mapVersion(), names));
    }
}
