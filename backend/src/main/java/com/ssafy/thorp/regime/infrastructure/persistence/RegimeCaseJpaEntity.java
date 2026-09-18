package com.ssafy.thorp.regime.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * {@code regime_search_case}의 JPA 매핑. snapshot 하나에 최대 10행이다(spec FR-013).
 *
 * <p>{@code eraPosition}은 nullable이다 — 사례의 모든 지표가 시대별 보조 설명을 만들지 못했다는 뜻이며, <b>0으로 채우지 않는다</b>.
 */
@Entity
@Table(name = "regime_search_case")
public class RegimeCaseJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long snapshotId;

    @Column(name = "\"rank\"", nullable = false)
    private short rank;

    @Column(nullable = false)
    private LocalDate endMonth;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> distance;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> indicatorValues;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> explanation;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> eraPosition;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    protected RegimeCaseJpaEntity() {}

    Long getSnapshotId() {
        return snapshotId;
    }

    short getRank() {
        return rank;
    }

    LocalDate getEndMonth() {
        return endMonth;
    }

    Map<String, Object> getDistance() {
        return distance;
    }

    Map<String, Object> getIndicatorValues() {
        return indicatorValues;
    }

    Map<String, Object> getExplanation() {
        return explanation;
    }

    Map<String, Object> getEraPosition() {
        return eraPosition;
    }
}
