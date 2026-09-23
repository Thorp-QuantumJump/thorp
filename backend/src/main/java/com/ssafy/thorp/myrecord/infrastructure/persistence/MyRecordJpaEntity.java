package com.ssafy.thorp.myrecord.infrastructure.persistence;

import com.ssafy.thorp.common.persistence.BaseEntity;
import com.ssafy.thorp.myrecord.domain.model.RecordKind;
import com.ssafy.thorp.myrecord.domain.vo.RecordId;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.time.Instant;
import java.time.LocalDate;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * {@code my_record}의 JPA 매핑.
 *
 * <p>{@code record_id}는 PK와 날짜를 이은 값이라 {@code persist()} 전에는 만들 수 없다. 날짜만 저장소가 정해 두고 결합은
 * {@code @PrePersist}가 한다 — Hibernate는 {@code persist()} 호출 시점의 필드 상태로 INSERT 문을 만들기 때문에 persist
 * 이후에 필드를 바꾸면 그 값이 INSERT에 실리지 않는다. {@code ParamSearchJpaEntity}와 같은 규약이다.
 *
 * <p>세 참조를 nullable 컬럼 셋으로 두는 이유는 스키마 주석에 있다 — 참조 무결성을 DB가 지키게 하려는 것이다. 종류와의 짝은 {@code
 * ck_my_record_ref_matches_kind}가 강제한다.
 */
@Entity
@Table(name = "my_record")
public class MyRecordJpaEntity extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "my_record_seq_generator")
    @SequenceGenerator(
            name = "my_record_seq_generator",
            sequenceName = "my_record_seq",
            allocationSize = 1)
    private Long id;

    @Column(nullable = false, length = 32, updatable = false)
    private String recordId;

    @Column(nullable = false, updatable = false)
    private Long ownerId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20, updatable = false)
    private RecordKind kind;

    @Column(updatable = false)
    private Long analysisRunId;

    @Column(updatable = false)
    private Long paramSearchId;

    @Column(updatable = false)
    private Long regimeLogId;

    @Column(nullable = false, length = 120)
    private String name;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(nullable = false, columnDefinition = "text[]", updatable = false)
    private String[] sectorCodes;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(nullable = false, columnDefinition = "text[]", updatable = false)
    private String[] strategyIds;

    @Column(updatable = false)
    private LocalDate periodStart;

    @Column(updatable = false)
    private LocalDate periodEnd;

    @Column(nullable = false, updatable = false)
    private Instant savedAt;

    private Instant lastViewedAt;

    private Instant deletedAt;

    @Column(nullable = false, updatable = false)
    private boolean isCopy;

    /** {@code record_id}에 붙일 날짜. 저장소가 시계에서 정하고 {@link #fillRecordId()}가 PK와 잇는다. */
    @Transient private LocalDate recordIdDate;

    protected MyRecordJpaEntity() {}

    MyRecordJpaEntity(
            Long ownerId,
            RecordKind kind,
            Long analysisRunId,
            Long paramSearchId,
            Long regimeLogId,
            String name,
            String[] sectorCodes,
            String[] strategyIds,
            LocalDate periodStart,
            LocalDate periodEnd,
            Instant savedAt,
            Instant lastViewedAt,
            Instant deletedAt,
            boolean isCopy) {
        this.ownerId = ownerId;
        this.kind = kind;
        this.analysisRunId = analysisRunId;
        this.paramSearchId = paramSearchId;
        this.regimeLogId = regimeLogId;
        this.name = name;
        this.sectorCodes = sectorCodes;
        this.strategyIds = strategyIds;
        this.periodStart = periodStart;
        this.periodEnd = periodEnd;
        this.savedAt = savedAt;
        this.lastViewedAt = lastViewedAt;
        this.deletedAt = deletedAt;
        this.isCopy = isCopy;
    }

    void assignRecordIdOn(LocalDate date) {
        if (this.recordId != null) {
            throw new IllegalStateException("recordId already assigned: " + this.recordId);
        }
        this.recordIdDate = date;
    }

    @PrePersist
    void fillRecordId() {
        if (recordId == null) {
            recordId = RecordId.of(recordIdDate, id).value();
        }
    }

    Long getId() {
        return id;
    }

    String getRecordId() {
        return recordId;
    }

    Long getOwnerId() {
        return ownerId;
    }

    RecordKind getKind() {
        return kind;
    }

    Long getAnalysisRunId() {
        return analysisRunId;
    }

    Long getParamSearchId() {
        return paramSearchId;
    }

    Long getRegimeLogId() {
        return regimeLogId;
    }

    String getName() {
        return name;
    }

    String[] getSectorCodes() {
        return sectorCodes;
    }

    String[] getStrategyIds() {
        return strategyIds;
    }

    LocalDate getPeriodStart() {
        return periodStart;
    }

    LocalDate getPeriodEnd() {
        return periodEnd;
    }

    Instant getSavedAt() {
        return savedAt;
    }

    Instant getLastViewedAt() {
        return lastViewedAt;
    }

    Instant getDeletedAt() {
        return deletedAt;
    }

    boolean isCopy() {
        return isCopy;
    }
}
