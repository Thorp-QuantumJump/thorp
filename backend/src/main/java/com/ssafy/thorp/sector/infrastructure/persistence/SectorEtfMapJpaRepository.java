package com.ssafy.thorp.sector.infrastructure.persistence;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 대응표 조회 전용 Spring Data 인터페이스.
 *
 * <p>{@code JpaRepository}가 저장·삭제 메서드를 함께 물려주지만, 그것은 이 인터페이스가 infrastructure 안에 갇혀 있어 밖에서 볼 수 없다.
 * 밖으로 나가는 계약은 {@code application.repository.SectorRepository}이고 거기에는 읽기 메서드뿐이다(research.md 결정 7).
 */
interface SectorEtfMapJpaRepository extends JpaRepository<SectorEtfMapJpaEntity, Long> {

    /**
     * 지금 열린 대응표 버전. 마이그레이션의 {@code uk_sector_etf_map_version_open}이 {@code effective_to IS NULL}인
     * 행을 최대 하나로 강제하므로 0건 또는 1건이다.
     */
    Optional<SectorEtfMapJpaEntity> findByEffectiveToIsNull();

    /**
     * 한 버전의 항목 전체. 섹터·대표 ETF를 {@code join fetch}로 함께 읽어 항목 수만큼의 추가 SELECT를 없앤다.
     *
     * <p>정렬을 섹터 코드 오름차순으로 고정한다. 정렬이 없으면 PostgreSQL이 돌려주는 순서가 물리적 저장 순서에 따라 달라져, 같은 대응표인데 화면의 섹터 순서가
     * 요청마다 바뀔 수 있다.
     */
    @Query(
            """
            select entry
            from SectorEtfMapEntryJpaEntity entry
            join fetch entry.sector sector
            join fetch entry.etf etf
            where entry.map.id = :mapId
            order by sector.code asc
            """)
    List<SectorEtfMapEntryJpaEntity> findEntriesByMapId(@Param("mapId") Long mapId);
}
