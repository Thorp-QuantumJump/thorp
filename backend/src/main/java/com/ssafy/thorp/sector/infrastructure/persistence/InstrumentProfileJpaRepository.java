package com.ssafy.thorp.sector.infrastructure.persistence;

import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 대표 상품 정보 조회 전용 Spring Data 인터페이스.
 *
 * <p>{@code JpaRepository}가 저장·삭제 메서드를 함께 물려주지만 이 인터페이스가 infrastructure 안에 갇혀 있어 밖에서 볼 수 없다. 밖으로
 * 나가는 계약은 {@code application.repository.InstrumentProfileRepository}이고 거기에는 읽기 메서드뿐이다.
 */
interface InstrumentProfileJpaRepository extends JpaRepository<InstrumentProfileJpaEntity, Long> {

    /**
     * 한 대응표 버전·한 종목의 대표 상품 정보를 자료 마감 시각 내림차순으로 읽는다. 호출자가 {@link Pageable}로 한 건만 가져간다.
     *
     * <p>{@code complete = true} 조건은 FR-014다. 이용 한도에 걸려 부분만 갱신된 자료로 만든 값을 완전한 자료처럼 내보내지 않는다.
     *
     * <p>마감 시각이 같은 행이 둘일 수 있으므로 {@code datasetId}로 한 번 더 가른다. 정렬이 흔들리면 같은 요청이 요청마다 다른 값을 돌려준다.
     */
    @Query(
            """
            select profile
            from InstrumentProfileJpaEntity profile
            join fetch profile.etf etf
            join fetch profile.datasetVersion version
            join version.mapVersion map
            where map.version = :mapVersion
              and version.complete = true
              and etf.instrumentId = :instrumentId
            order by version.dataDeadline desc, version.datasetId desc
            """)
    List<InstrumentProfileJpaEntity> findCurrent(
            @Param("mapVersion") String mapVersion,
            @Param("instrumentId") String instrumentId,
            Pageable pageable);
}
