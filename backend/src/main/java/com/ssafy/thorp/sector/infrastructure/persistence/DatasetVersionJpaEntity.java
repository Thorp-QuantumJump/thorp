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
import java.time.Instant;

/**
 * {@code dataset_version} 테이블 매핑 (FR-013·FR-014).
 *
 * <p>마이그레이션이 만든 컬럼 전부를 매핑하지 않는다. 대표 상품 정보 응답이 쓰는 것은 {@code datasetId}·{@code dataDeadline}·{@code
 * isSample}과 고르는 조건인 {@code complete}·{@code mapVersion}뿐이다. {@code collected_at}·{@code
 * last_success_at}·{@code failure_reason}·{@code source}는 읽는 곳이 생길 때 그 코드와 같은 MR에서 붙인다({@code
 * backend/CLAUDE.md}). {@code ddl-auto=validate}는 매핑된 컬럼만 보고, 미매핑 컬럼의 존재는 {@code
 * FlywayMigrationTest}가 지킨다.
 *
 * <p>시계열(가격·기업행위·지수)은 매핑하지 않는다. 그것을 읽는 것은 워커이고 Backend는 메타데이터만 본다(plan.md).
 */
@Entity
@Table(name = "dataset_version")
class DatasetVersionJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "dataset_id", nullable = false, unique = true, length = 64)
    private String datasetId;

    /** 데이터 마감 시각. 화면이 "어느 시점 자료인지" 판단하는 근거다(FR-013). */
    @Column(name = "data_deadline", nullable = false)
    private Instant dataDeadline;

    /** false면 이용 한도 등으로 부분만 갱신된 자료다. 완전한 자료로 표시하지 않는다(FR-014). */
    @Column(name = "complete", nullable = false)
    private boolean complete;

    /** FR-002, research.md 결정 5. 예시 자료로 만든 자료 버전인지. */
    @Column(name = "is_sample", nullable = false)
    private boolean isSample;

    /** 이 자료가 어느 대응표 버전을 대상으로 수집됐는지. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "map_version_id", nullable = false)
    private SectorEtfMapJpaEntity mapVersion;

    protected DatasetVersionJpaEntity() {}

    Long getId() {
        return id;
    }

    String getDatasetId() {
        return datasetId;
    }

    Instant getDataDeadline() {
        return dataDeadline;
    }

    boolean isComplete() {
        return complete;
    }

    boolean isSample() {
        return isSample;
    }

    SectorEtfMapJpaEntity getMapVersion() {
        return mapVersion;
    }
}
