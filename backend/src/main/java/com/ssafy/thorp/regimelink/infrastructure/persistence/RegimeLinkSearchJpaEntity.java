package com.ssafy.thorp.regimelink.infrastructure.persistence;

import com.ssafy.thorp.regimelink.domain.vo.LinkSearchId;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * {@code regime_link_search}의 JPA 매핑 (data-model §3).
 *
 * <p>{@code retention}은 저장만 하고 읽어서 화면에 보내지 않는다(FR-021). 응답 DTO에 그 자리를 만들지 않는 것으로 막는다.
 */
@Entity
@Table(name = "regime_link_search")
public class RegimeLinkSearchJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "regime_link_search_seq_gen")
    @SequenceGenerator(
            name = "regime_link_search_seq_gen",
            sequenceName = "regime_link_search_seq",
            allocationSize = 1)
    private Long id;

    @Column(nullable = false, unique = true, length = 32)
    private String searchId;

    @Column(nullable = false)
    private Long ownerUserId;

    @Column(nullable = false, length = 24)
    private String entryPath;

    private Long snapshotId;

    @Column(nullable = false, length = 200)
    private String snapshotKey;

    @Column(nullable = false, length = 16)
    private String mode;

    @Column(length = 32)
    private String fieldId;

    @Column(nullable = false)
    private LocalDate baselineMonth;

    @Column(nullable = false)
    private Long baseRunId;

    private Long compareRunId;

    private Long baseRequestId;

    private Long compareRequestId;

    private Long filterId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private List<Map<String, Object>> shownCases;

    @Column(nullable = false)
    private short excludedCount;

    @Column(nullable = false)
    private boolean fullSearchShown;

    private Long previousSearchId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> retention;

    @Column(nullable = false, length = 24)
    private String basis;

    @Column(nullable = false, length = 64)
    private String clientRequestId;

    @Column(nullable = false)
    private Instant createdAt;

    /** {@code search_id}에 쓸 날짜 접두. PK 와의 결합은 {@code @PrePersist} 가 한다. */
    @Transient private LocalDate searchIdDate;

    protected RegimeLinkSearchJpaEntity() {}

    /** PK 채번 뒤·INSERT 문 생성 전. 외부 식별자를 날짜와 순번으로 여기서 짓는다. */
    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
        if (searchId == null) {
            searchId = LinkSearchId.of(searchIdDate, id).value();
        }
    }

    public Long getId() {
        return id;
    }

    public String getSearchId() {
        return searchId;
    }

    public Long getOwnerUserId() {
        return ownerUserId;
    }

    public String getEntryPath() {
        return entryPath;
    }

    public Long getSnapshotId() {
        return snapshotId;
    }

    public String getSnapshotKey() {
        return snapshotKey;
    }

    public String getMode() {
        return mode;
    }

    public String getFieldId() {
        return fieldId;
    }

    public LocalDate getBaselineMonth() {
        return baselineMonth;
    }

    public Long getBaseRunId() {
        return baseRunId;
    }

    public Long getCompareRunId() {
        return compareRunId;
    }

    public Long getBaseRequestId() {
        return baseRequestId;
    }

    public Long getCompareRequestId() {
        return compareRequestId;
    }

    public Long getFilterId() {
        return filterId;
    }

    public List<Map<String, Object>> getShownCases() {
        return shownCases;
    }

    public short getExcludedCount() {
        return excludedCount;
    }

    public boolean isFullSearchShown() {
        return fullSearchShown;
    }

    public Long getPreviousSearchId() {
        return previousSearchId;
    }

    public Map<String, Object> getRetention() {
        return retention;
    }

    public String getBasis() {
        return basis;
    }

    public String getClientRequestId() {
        return clientRequestId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    /** 저장 전 값 채우기. 생성자 인자가 스무 개를 넘어 가독성을 잃으므로 빌더 대신 설정 메서드 하나로 묶는다. */
    public static final class Builder {

        private final RegimeLinkSearchJpaEntity entity = new RegimeLinkSearchJpaEntity();

        public Builder owner(Long ownerUserId) {
            entity.ownerUserId = ownerUserId;
            return this;
        }

        public Builder entryPath(String entryPath) {
            entity.entryPath = entryPath;
            return this;
        }

        public Builder snapshot(Long snapshotId, String snapshotKey) {
            entity.snapshotId = snapshotId;
            entity.snapshotKey = snapshotKey;
            return this;
        }

        public Builder search(String mode, String fieldId, LocalDate baselineMonth) {
            entity.mode = mode;
            entity.fieldId = fieldId;
            entity.baselineMonth = baselineMonth;
            return this;
        }

        public Builder runs(Long baseRunId, Long compareRunId) {
            entity.baseRunId = baseRunId;
            entity.compareRunId = compareRunId;
            return this;
        }

        public Builder requests(Long baseRequestId, Long compareRequestId) {
            entity.baseRequestId = baseRequestId;
            entity.compareRequestId = compareRequestId;
            return this;
        }

        public Builder filter(Long filterId) {
            entity.filterId = filterId;
            return this;
        }

        public Builder cases(List<Map<String, Object>> shownCases, int excludedCount) {
            entity.shownCases = shownCases;
            entity.excludedCount = (short) excludedCount;
            return this;
        }

        public Builder fullSearchShown(boolean fullSearchShown) {
            entity.fullSearchShown = fullSearchShown;
            return this;
        }

        public Builder previous(Long previousSearchId, Map<String, Object> retention) {
            entity.previousSearchId = previousSearchId;
            entity.retention = retention;
            return this;
        }

        public Builder basis(String basis) {
            entity.basis = basis;
            return this;
        }

        public Builder clientRequestId(String clientRequestId) {
            entity.clientRequestId = clientRequestId;
            return this;
        }

        /** 외부 식별자를 지을 날짜. 저장 시각이 아니라 호출자의 시계를 쓴다. */
        public Builder searchIdDate(LocalDate searchIdDate) {
            entity.searchIdDate = searchIdDate;
            return this;
        }

        public RegimeLinkSearchJpaEntity build() {
            return entity;
        }
    }
}
