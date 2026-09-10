package com.ssafy.thorp.paramsearch.infrastructure.persistence;

import com.ssafy.thorp.common.persistence.BaseEntity;
import com.ssafy.thorp.paramsearch.domain.model.CandidateStatus;
import com.ssafy.thorp.paramsearch.domain.model.SearchCandidate;
import com.ssafy.thorp.paramsearch.domain.model.ValidationLabel;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import java.util.Map;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * {@code search_candidate} 테이블 매핑. 도메인 모델과는 {@link SearchCandidateMapper}로 변환한다.
 *
 * <p><b>{@code allocationSize = 50}</b>이며 마이그레이션의 {@code search_candidate_seq INCREMENT BY 50}과 같아야
 * 한다. 후보는 묶음당 수백~천 건씩 한 번에 INSERT되므로 건마다 시퀀스를 왕복하면 접수가 느려진다. {@code backtest_trade}와 같은 이유·같은 값이다.
 *
 * <p>성과 숫자 컬럼이 없다. 수익·하락 폭·거래비용은 {@code backtest_result}가 원본이고 여기서는 {@code backtestJobId}로 가리킨다.
 * 제외·미실행 후보는 그 값이 아예 없으며 <b>0으로 채우지 않는다</b>(FR-011, 원칙 VI).
 */
@Entity
@Table(name = "search_candidate")
public class SearchCandidateJpaEntity extends BaseEntity {

    @Id
    @GeneratedValue(
            strategy = GenerationType.SEQUENCE,
            generator = "search_candidate_seq_generator")
    @SequenceGenerator(
            name = "search_candidate_seq_generator",
            sequenceName = "search_candidate_seq",
            allocationSize = 50)
    private Long id;

    @Column(nullable = false)
    private Long paramSearchId;

    @Column(nullable = false)
    private int candidateNo;

    @Column(nullable = false, length = SearchCandidate.MAX_STRATEGY_ID_LENGTH)
    private String strategyId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private Map<String, Object> parameters;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private Map<String, Integer> gridCoordinates;

    /** 발행 전·제외·미실행 후보는 NULL이다. */
    @Column private Long backtestJobId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private CandidateStatus status;

    @Column(length = SearchCandidate.MAX_REASON_CODE_LENGTH)
    private String reasonCode;

    @Column(columnDefinition = "TEXT")
    private String reasonDetail;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column
    private Map<String, Object> stability;

    @Column(nullable = false)
    private int retryCount;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column
    private Map<String, Object> periodPerformance;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private ValidationLabel validationLabel;

    @Column(nullable = false)
    private boolean selected;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column
    private Map<String, Object> selectionReason;

    protected SearchCandidateJpaEntity() {}

    SearchCandidateJpaEntity(
            Long id,
            Long paramSearchId,
            int candidateNo,
            String strategyId,
            Map<String, Object> parameters,
            Map<String, Integer> gridCoordinates,
            Long backtestJobId,
            CandidateStatus status,
            String reasonCode,
            String reasonDetail,
            Map<String, Object> stability,
            int retryCount,
            Map<String, Object> periodPerformance,
            ValidationLabel validationLabel,
            boolean selected,
            Map<String, Object> selectionReason) {
        this.id = id;
        this.paramSearchId = paramSearchId;
        this.candidateNo = candidateNo;
        this.strategyId = strategyId;
        this.parameters = parameters;
        this.gridCoordinates = gridCoordinates;
        this.backtestJobId = backtestJobId;
        this.status = status;
        this.reasonCode = reasonCode;
        this.reasonDetail = reasonDetail;
        this.stability = stability;
        this.retryCount = retryCount;
        this.periodPerformance = periodPerformance;
        this.validationLabel = validationLabel;
        this.selected = selected;
        this.selectionReason = selectionReason;
    }

    /**
     * 도메인에서 바뀔 수 있는 값을 반영한다. 전이 규칙은 도메인이 이미 검증했으므로 여기서는 값만 바꾼다.
     *
     * <p>조합 자체({@code parameters}·{@code gridCoordinates}·{@code candidateNo})는 여기에 없다. 후보의 정체를 이루는
     * 값이라 바뀌면 재현 기록이 뜻을 잃는다(FR-020).
     */
    void applyProgress(
            Long backtestJobId,
            CandidateStatus status,
            String reasonCode,
            String reasonDetail,
            Map<String, Object> stability,
            int retryCount,
            Map<String, Object> periodPerformance,
            ValidationLabel validationLabel,
            boolean selected,
            Map<String, Object> selectionReason) {
        this.backtestJobId = backtestJobId;
        this.status = status;
        this.reasonCode = reasonCode;
        this.reasonDetail = reasonDetail;
        this.stability = stability;
        this.retryCount = retryCount;
        this.periodPerformance = periodPerformance;
        this.validationLabel = validationLabel;
        this.selected = selected;
        this.selectionReason = selectionReason;
    }

    public Long getId() {
        return id;
    }

    public Long getParamSearchId() {
        return paramSearchId;
    }

    public int getCandidateNo() {
        return candidateNo;
    }

    public String getStrategyId() {
        return strategyId;
    }

    public Map<String, Object> getParameters() {
        return parameters;
    }

    public Map<String, Integer> getGridCoordinates() {
        return gridCoordinates;
    }

    public Long getBacktestJobId() {
        return backtestJobId;
    }

    public CandidateStatus getStatus() {
        return status;
    }

    public String getReasonCode() {
        return reasonCode;
    }

    public String getReasonDetail() {
        return reasonDetail;
    }

    public Map<String, Object> getStability() {
        return stability;
    }

    public int getRetryCount() {
        return retryCount;
    }

    public Map<String, Object> getPeriodPerformance() {
        return periodPerformance;
    }

    public ValidationLabel getValidationLabel() {
        return validationLabel;
    }

    public boolean isSelected() {
        return selected;
    }

    public Map<String, Object> getSelectionReason() {
        return selectionReason;
    }
}
