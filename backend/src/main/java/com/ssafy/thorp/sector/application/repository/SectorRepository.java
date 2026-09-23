package com.ssafy.thorp.sector.application.repository;

import com.ssafy.thorp.sector.application.result.SectorListResult;
import java.util.Optional;

/**
 * 섹터-ETF 대응표 조회. <b>읽기 전용이다.</b>
 *
 * <p>대응표의 원본은 {@code data/} 파이프라인이 소유하고 관계형 저장소의 네 표는 그 원본을 반영한 사본이다(research.md 결정 7). 그래서 이
 * 인터페이스에 {@code save}·{@code update}·{@code delete} 같은 쓰기 메서드를 두지 않는다. 양방향을 허용하면 같은 대응표가 두 곳에서
 * 갈라지고, FR-003이 요구하는 버전 보존을 누가 책임지는지 흐려진다. 버전을 여닫는 순서(이전 버전을 닫고 새 버전을 여는 한 트랜잭션)도 파이프라인 한 곳에만 있다.
 */
public interface SectorRepository {

    /**
     * 현재 열린 대응표 버전과 그 버전의 섹터 목록을 읽는다.
     *
     * <p>"열린 버전"은 {@code sector_etf_map_version.effective_to IS NULL}인 행이다. 마이그레이션의 부분 UNIQUE 인덱스
     * {@code uk_sector_etf_map_version_open}이 그런 행을 최대 하나로 강제한다.
     *
     * @return 열린 버전이 없으면 {@link Optional#empty()}. 호출자는 이것을 빈 목록이 아니라 "대응표 미발행"으로 다룬다
     */
    Optional<SectorListResult> findOpenSectorMap();
}
