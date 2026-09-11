package com.ssafy.thorp.paramsearch.infrastructure.persistence;

import com.ssafy.thorp.common.persistence.BaseEntity;
import com.ssafy.thorp.paramsearch.domain.model.ParamSearch;
import com.ssafy.thorp.paramsearch.domain.model.ParamSearchStatus;
import com.ssafy.thorp.paramsearch.domain.model.SearchMethod;
import com.ssafy.thorp.paramsearch.domain.model.StopReason;
import com.ssafy.thorp.paramsearch.domain.vo.ParamSearchId;
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
import java.util.List;
import java.util.Map;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * {@code param_search} 테이블 매핑. 도메인 모델과는 {@link ParamSearchMapper}로 변환한다.
 *
 * <p>PK가 SEQUENCE라 {@code persist()} 시점에 값이 나오고, 그 PK로 {@code search_id}를 INSERT 전에 채우므로 컬럼을 NOT
 * NULL로 둘 수 있고 사후 UPDATE가 없다. 결합은 {@code @PrePersist}에서 한다 — Hibernate는 {@code persist()} 호출 시점의 필드
 * 상태로 INSERT 문을 만들기 때문에 persist 이후에 필드를 바꾸면 그 값이 INSERT에 실리지 않는다. {@code BacktestJobJpaEntity}와 같은
 * 규약이다.
 *
 * <p>JSONB 컬럼이 많은 이유는 V2의 저장 규칙을 따르기 때문이다 — 질의에 쓰는 값만 컬럼이고 표시용 묶음은 JSONB다. {@code counts} 여섯 값이나
 * {@code selection_rule}은 함께 갱신되고 개별 필터·정렬 대상이 아니다.
 */
@Entity
@Table(name = "param_search")
public class ParamSearchJpaEntity extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "param_search_seq_generator")
    @SequenceGenerator(
            name = "param_search_seq_generator",
            sequenceName = "param_search_seq",
            allocationSize = 1)
    private Long id;

    @Column(nullable = false, unique = true, length = 32)
    private String searchId;

    /** 인증 도입 전이라 NULL이다(research R4). */
    @Column private Long ownerUserId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private List<String> strategyIds;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private List<String> instrumentIds;

    @Column(nullable = false, length = ParamSearch.MAX_REGISTRY_VERSION_LENGTH)
    private String registryVersion;

    /**
     * 전략 ID -> 그 전략의 축과 실제 탐색한 후보값(data-model.md §2.1).
     *
     * <p><b>{@code Map<String, Object>}로 두지 않는다.</b> 값 자리를 {@code Object}로 선언하면 Jackson이 untyped
     * 역직렬화 경로를 타는데, 클래스패스에 {@code jackson-module-scala}가 있으면(테스트에서 {@code spring-kafka-test}가 끌어온다)
     * JSON 배열이 {@code java.util.List}가 아닌 Scala {@code List}로 돌아온다. 실제 타입을 선언하면 Jackson이 타입 있는 컬렉션
     * 역직렬화를 써서 환경에 상관없이 {@code java.util.List}가 된다.
     *
     * <p>전략은 객체 키이고 축은 배열이다. 전략 사이에는 순서의 뜻이 없지만 축 순서는 그리드 좌표의 뜻이고, {@code jsonb}는 객체 키 순서를 보존하지
     * 않는다(FR-029).
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private Map<String, SearchSpaceDocument> searchSpace;

    /** 평가 분할이 미등록이면 NULL이고 그 결과 전 후보가 미검증이다(research R5). */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column
    private Map<String, Object> evaluationSplit;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private SearchMethod searchMethod;

    /** 무작위 표본 도입 전까지 항상 NULL이다. */
    @Column private Integer randomSeed;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private Map<String, Object> selectionRule;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private Map<String, Object> limits;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private Map<String, Object> referenceVersions;

    /** 접수 시점에 고정한 실행 조건(구간·금액·비용·대응표·워밍업·기준 전략). 후보와 기준이 이 값으로 조립된다. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private Map<String, Object> executionPlan;

    @Column private Long baselineJobId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ParamSearchStatus status;

    @Enumerated(EnumType.STRING)
    @Column(length = 32)
    private StopReason stopReason;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private Map<String, Object> counts;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column
    private Map<String, Object> selection;

    @Column(nullable = false)
    private boolean dataVersionChanged;

    @Column private Instant startedAt;

    @Column private Instant finishedAt;

    /** searchId에 쓸 날짜 접두. 저장 전 한 번 예약하고 PK와의 결합은 PrePersist가 한다. */
    @Transient private LocalDate searchIdDate;

    protected ParamSearchJpaEntity() {}

    ParamSearchJpaEntity(
            Long id,
            String searchId,
            Long ownerUserId,
            List<String> strategyIds,
            List<String> instrumentIds,
            String registryVersion,
            Map<String, SearchSpaceDocument> searchSpace,
            Map<String, Object> evaluationSplit,
            SearchMethod searchMethod,
            Integer randomSeed,
            Map<String, Object> selectionRule,
            Map<String, Object> limits,
            Map<String, Object> referenceVersions,
            Map<String, Object> executionPlan,
            Long baselineJobId,
            ParamSearchStatus status,
            StopReason stopReason,
            Map<String, Object> counts,
            Map<String, Object> selection,
            boolean dataVersionChanged,
            Instant startedAt,
            Instant finishedAt) {
        this.id = id;
        this.searchId = searchId;
        this.ownerUserId = ownerUserId;
        this.strategyIds = strategyIds;
        this.instrumentIds = instrumentIds;
        this.registryVersion = registryVersion;
        this.searchSpace = searchSpace;
        this.evaluationSplit = evaluationSplit;
        this.searchMethod = searchMethod;
        this.randomSeed = randomSeed;
        this.selectionRule = selectionRule;
        this.limits = limits;
        this.referenceVersions = referenceVersions;
        this.executionPlan = executionPlan;
        this.baselineJobId = baselineJobId;
        this.status = status;
        this.stopReason = stopReason;
        this.counts = counts;
        this.selection = selection;
        this.dataVersionChanged = dataVersionChanged;
        this.startedAt = startedAt;
        this.finishedAt = finishedAt;
    }

    /** 저장 직전 한 번만 외부 식별자를 예약한다. 이미 값이 있으면 거부한다. */
    void assignSearchIdOn(LocalDate date) {
        if (this.searchId != null) {
            throw new IllegalStateException("searchId already assigned: " + this.searchId);
        }
        this.searchIdDate = date;
    }

    /**
     * 도메인에서 바뀔 수 있는 값을 반영한다. 전이 규칙은 도메인이 이미 검증했으므로 여기서는 값만 바꾼다.
     *
     * <p>접수 시점 스냅샷({@code searchSpace}·{@code selectionRule}·{@code limits}·{@code
     * referenceVersions})은 여기에 없다. 이후 바뀌지 않아야 하는 값이므로 갱신 경로를 아예 두지 않는다(FR-013c).
     */
    void applyProgress(
            ParamSearchStatus status,
            StopReason stopReason,
            Map<String, Object> counts,
            Map<String, Object> selection,
            Long baselineJobId,
            boolean dataVersionChanged,
            Instant startedAt,
            Instant finishedAt) {
        this.status = status;
        this.stopReason = stopReason;
        this.counts = counts;
        this.selection = selection;
        this.baselineJobId = baselineJobId;
        this.dataVersionChanged = dataVersionChanged;
        this.startedAt = startedAt;
        this.finishedAt = finishedAt;
    }

    @PrePersist
    void fillSearchId() {
        if (searchId == null) {
            searchId = ParamSearchId.of(searchIdDate, id).value();
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

    public List<String> getStrategyIds() {
        return strategyIds;
    }

    public List<String> getInstrumentIds() {
        return instrumentIds;
    }

    public String getRegistryVersion() {
        return registryVersion;
    }

    public Map<String, SearchSpaceDocument> getSearchSpace() {
        return searchSpace;
    }

    public Map<String, Object> getEvaluationSplit() {
        return evaluationSplit;
    }

    public SearchMethod getSearchMethod() {
        return searchMethod;
    }

    public Integer getRandomSeed() {
        return randomSeed;
    }

    public Map<String, Object> getSelectionRule() {
        return selectionRule;
    }

    public Map<String, Object> getLimits() {
        return limits;
    }

    public Map<String, Object> getReferenceVersions() {
        return referenceVersions;
    }

    public Map<String, Object> getExecutionPlan() {
        return executionPlan;
    }

    public Long getBaselineJobId() {
        return baselineJobId;
    }

    public ParamSearchStatus getStatus() {
        return status;
    }

    public StopReason getStopReason() {
        return stopReason;
    }

    public Map<String, Object> getCounts() {
        return counts;
    }

    public Map<String, Object> getSelection() {
        return selection;
    }

    public boolean isDataVersionChanged() {
        return dataVersionChanged;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getFinishedAt() {
        return finishedAt;
    }
}
