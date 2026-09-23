package com.ssafy.thorp.learning.application.port;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 001이 소유하는 섹터-ETF 대응표를 읽는 포트(research R8). 예시 섹터를 조회 시점과 게시 시점에 판정할 때 쓴다.
 *
 * <p>013은 대응표를 고치지 않고 판정 결과를 저장하지도 않는다.
 */
public interface SectorMapPort {

    /**
     * 지금 열린 대응표.
     *
     * @return 대응표가 아직 발행되지 않았으면 {@link Optional#empty()}. 빈 맵으로 대신하지 않는다. "대응표가 없다"와 "섹터가 0개다"는 다른
     *     사실이다(원칙 VI)
     */
    Optional<SectorMapView> currentMap();

    /**
     * 판정에 필요한 만큼만 옮겨 담은 대응표.
     *
     * @param mapVersion 001의 {@code mapVersion}. 게시 판이 기록한다
     * @param sectorNamesByCode 섹터 코드 → 섹터 이름
     */
    record SectorMapView(String mapVersion, Map<String, String> sectorNamesByCode) {

        public SectorMapView {
            Objects.requireNonNull(mapVersion, "mapVersion must not be null");
            sectorNamesByCode = Map.copyOf(sectorNamesByCode);
        }
    }
}
