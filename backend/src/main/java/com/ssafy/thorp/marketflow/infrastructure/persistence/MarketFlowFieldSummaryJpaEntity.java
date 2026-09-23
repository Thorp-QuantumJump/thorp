package com.ssafy.thorp.marketflow.infrastructure.persistence;

import com.ssafy.thorp.marketflow.domain.vo.FieldStatus;
import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.List;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** {@code market_flow_field_summary}의 JPA 매핑. 지표 개별 값은 008과 이중 저장하지 않는다. */
@Entity
@Table(name = "market_flow_field_summary")
public class MarketFlowFieldSummaryJpaEntity {

    @EmbeddedId private MarketFlowFieldSummaryId id;

    @Column(nullable = false, length = 64)
    private String displayName;

    @Column(nullable = false)
    private int displayOrder;

    @Column(columnDefinition = "text")
    private String narrative;

    @Column(nullable = false, length = 128)
    private String narrativeRuleVersion;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private List<String> narrativeSubjects;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private FieldStatus status;

    @Column(nullable = false)
    private boolean isExampleData;

    @Column(length = 7)
    private String referenceMonth;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private List<String> referenceMonthHeldBackBy;

    private Instant lastUpdatedAt;

    @Column(nullable = false)
    private Instant builtAt;

    protected MarketFlowFieldSummaryJpaEntity() {}

    MarketFlowFieldSummaryJpaEntity(
            MarketFlowFieldSummaryId id,
            String displayName,
            int displayOrder,
            String narrative,
            String narrativeRuleVersion,
            List<String> narrativeSubjects,
            FieldStatus status,
            boolean isExampleData,
            String referenceMonth,
            List<String> referenceMonthHeldBackBy,
            Instant lastUpdatedAt,
            Instant builtAt) {
        this.id = id;
        this.displayName = displayName;
        this.displayOrder = displayOrder;
        this.narrative = narrative;
        this.narrativeRuleVersion = narrativeRuleVersion;
        this.narrativeSubjects = List.copyOf(narrativeSubjects);
        this.status = status;
        this.isExampleData = isExampleData;
        this.referenceMonth = referenceMonth;
        this.referenceMonthHeldBackBy = List.copyOf(referenceMonthHeldBackBy);
        this.lastUpdatedAt = lastUpdatedAt;
        this.builtAt = builtAt;
    }

    MarketFlowFieldSummaryId getId() {
        return id;
    }

    String getDisplayName() {
        return displayName;
    }

    int getDisplayOrder() {
        return displayOrder;
    }

    String getNarrative() {
        return narrative;
    }

    String getNarrativeRuleVersion() {
        return narrativeRuleVersion;
    }

    List<String> getNarrativeSubjects() {
        return narrativeSubjects;
    }

    FieldStatus getStatus() {
        return status;
    }

    boolean isExampleData() {
        return isExampleData;
    }

    String getReferenceMonth() {
        return referenceMonth;
    }

    List<String> getReferenceMonthHeldBackBy() {
        return referenceMonthHeldBackBy;
    }

    Instant getLastUpdatedAt() {
        return lastUpdatedAt;
    }
}
