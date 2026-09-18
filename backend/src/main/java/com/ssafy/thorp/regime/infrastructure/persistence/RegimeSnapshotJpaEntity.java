package com.ssafy.thorp.regime.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * {@code regime_search_snapshot}의 JPA 매핑.
 *
 * <p><b>읽기 전용이다.</b> 이 엔티티로 저장하는 코드는 없다 — 값을 넣는 것은 {@code data/} 배치뿐이다.
 *
 * <p>JSONB 다섯은 {@code Map}·{@code List}로 그대로 받는다. decimal이 문자열로 들어 있어 여기서 수로 바꾸면 자릿수가 깎이므로, {@link
 * RegimeSnapshotMapper}가 도메인으로 옮길 때 {@code BigDecimal(String)}으로 <b>표기를 보존하며</b> 읽는다.
 */
@Entity
@Table(name = "regime_search_snapshot")
public class RegimeSnapshotJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 200)
    private String snapshotKey;

    @Column(nullable = false, length = 16)
    private String mode;

    @Column(length = 32)
    private String fieldId;

    @Column(nullable = false)
    private LocalDate baselineMonth;

    @Column(nullable = false)
    private int revision;

    @Column(nullable = false, length = 64, columnDefinition = "bpchar")
    private String contentHash;

    @Column(nullable = false, length = 40)
    private String dataVersion;

    @Column(nullable = false, length = 40)
    private String registryVersion;

    @Column(nullable = false, length = 40)
    private String modelVersion;

    @Column(nullable = false, length = 24)
    private String status;

    @Column(length = 48)
    private String statusReason;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> scale;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> comparisonMonths;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private List<Map<String, Object>> rankedCandidates;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> candidateCounts;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> dataAsof;

    @Column(nullable = false)
    private Instant computedAt;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    protected RegimeSnapshotJpaEntity() {}

    Long getId() {
        return id;
    }

    String getSnapshotKey() {
        return snapshotKey;
    }

    String getMode() {
        return mode;
    }

    String getFieldId() {
        return fieldId;
    }

    LocalDate getBaselineMonth() {
        return baselineMonth;
    }

    int getRevision() {
        return revision;
    }

    String getDataVersion() {
        return dataVersion;
    }

    String getRegistryVersion() {
        return registryVersion;
    }

    String getModelVersion() {
        return modelVersion;
    }

    String getStatus() {
        return status;
    }

    String getStatusReason() {
        return statusReason;
    }

    Map<String, Object> getScale() {
        return scale;
    }

    Map<String, Object> getComparisonMonths() {
        return comparisonMonths;
    }

    List<Map<String, Object>> getRankedCandidates() {
        return rankedCandidates;
    }

    Map<String, Object> getCandidateCounts() {
        return candidateCounts;
    }

    Map<String, Object> getDataAsof() {
        return dataAsof;
    }

    Instant getComputedAt() {
        return computedAt;
    }
}
