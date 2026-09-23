package com.ssafy.thorp.sector.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * 대응표 한 판 = {@code sector_etf_map_version} 테이블 매핑 (FR-003).
 *
 * <p>버전 행 하나가 곧 "그 시점의 대응표"이고 항목은 {@link SectorEtfMapEntryJpaEntity}가 딸린다.
 *
 * <p>{@code effectiveTo}만 시각을 매핑한다. 조회가 쓰는 유일한 조건이 "지금 열린 버전"({@code effective_to IS NULL})이기 때문이다.
 * 마이그레이션의 부분 UNIQUE 인덱스 {@code uk_sector_etf_map_version_open}이 열린 행을 최대 하나로 강제하므로, 이 조건은 반드시 0건 또는
 * 1건이다. {@code effective_from}·{@code published_at}은 읽지 않으므로 매핑하지 않는다.
 */
@Entity
@Table(name = "sector_etf_map_version")
class SectorEtfMapJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "version", nullable = false, unique = true, length = 64)
    private String version;

    /** NULL이면 열린 버전이다. */
    @Column(name = "effective_to")
    private Instant effectiveTo;

    /** FR-002. 예시 대응표로 동작하는 동안 TRUE이고 화면이 이 값을 읽어 예시임을 표시한다. */
    @Column(name = "is_sample", nullable = false)
    private boolean isSample;

    protected SectorEtfMapJpaEntity() {}

    Long getId() {
        return id;
    }

    String getVersion() {
        return version;
    }

    Instant getEffectiveTo() {
        return effectiveTo;
    }

    boolean isSample() {
        return isSample;
    }
}
