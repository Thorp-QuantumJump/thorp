package com.ssafy.thorp.sector.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/**
 * {@code sector_etf_map_entry} 테이블 매핑. 한 대응표 버전 안에서 섹터 하나와 대표 ETF 하나를 잇는 행이다.
 *
 * <p>{@link SectorEtfMapJpaEntity}(버전)와 이 표를 나눠 매핑하는 이유는 표가 실제로 둘이고, 같은 섹터가 버전마다 다른 ETF를 가리킬 수 있기
 * 때문이다. 섹터·ETF는 버전을 타지 않는 공용 표라 {@code @ManyToOne}이다.
 *
 * <p>세 연관 모두 {@code LAZY}로 두고 조회 질의가 {@code join fetch}로 한 번에 읽는다. 기본값인 EAGER는 목록 질의에서 항목 수만큼 추가
 * SELECT를 만든다.
 */
@Entity
@Table(name = "sector_etf_map_entry")
class SectorEtfMapEntryJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "map_version_id", nullable = false)
    private SectorEtfMapJpaEntity map;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "sector_id", nullable = false)
    private SectorJpaEntity sector;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "etf_id", nullable = false)
    private RepresentativeEtfJpaEntity etf;

    protected SectorEtfMapEntryJpaEntity() {}

    Long getId() {
        return id;
    }

    SectorEtfMapJpaEntity getMap() {
        return map;
    }

    SectorJpaEntity getSector() {
        return sector;
    }

    RepresentativeEtfJpaEntity getEtf() {
        return etf;
    }
}
