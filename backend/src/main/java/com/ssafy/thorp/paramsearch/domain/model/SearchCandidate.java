package com.ssafy.thorp.paramsearch.domain.model;

import com.ssafy.thorp.paramsearch.domain.vo.GridCoordinates;
import com.ssafy.thorp.paramsearch.domain.vo.ParameterCombination;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 탐색 후보 하나. 묶음 안에서 파라미터 조합 1건 = 백테스트 Job 1건에 대응한다(research R6).
 *
 * <p><b>성과 숫자를 담지 않는다.</b> 수익·하락 폭·거래비용은 {@code backtest_result}가 원본이고 이 모델은 {@code backtestJobId}로
 * 그것을 가리킨다. 이 모델이 갖는 것은 묶음 맥락의 값 — 그리드 좌표, 상태와 사유, 안정성, 선정 여부다.
 *
 * <p><b>계산하지 않은 후보도 남는다.</b> {@link CandidateStatus#EXCLUDED}(유효성 위반, FR-006)와 {@link
 * CandidateStatus#SKIPPED}(취소·한도로 미실행)는 {@code backtestJobId}가 없고 사유만 갖는다. 이 후보들의 성과 값 자리를 0으로 채우지
 * 않는다 — 낮은 수익과 구분되어야 한다(FR-011, 원칙 VI).
 *
 * <p>불변 객체이며 상태 전이는 새 인스턴스를 돌려준다.
 */
public class SearchCandidate {

    /** 저장 컬럼 폭과 같은 값. */
    public static final int MAX_STRATEGY_ID_LENGTH = 64;

    /** 저장 컬럼 폭과 같은 값. */
    public static final int MAX_REASON_CODE_LENGTH = 64;

    private final Long id;
    private final Long paramSearchId;
    private final int candidateNo;
    private final String strategyId;
    private final ParameterCombination parameters;
    private final GridCoordinates gridCoordinates;
    private final Long backtestJobId;
    private final CandidateStatus status;
    private final String reasonCode;
    private final String reasonDetail;
    private final Map<String, Object> stability;
    private final int retryCount;
    private final Map<String, Object> periodPerformance;
    private final ValidationLabel validationLabel;
    private final boolean selected;
    private final Map<String, Object> selectionReason;

    private SearchCandidate(Builder builder) {
        this.id = builder.id;
        this.paramSearchId = builder.paramSearchId;
        this.candidateNo = builder.candidateNo;
        this.strategyId = builder.strategyId;
        this.parameters = builder.parameters;
        this.gridCoordinates = builder.gridCoordinates;
        this.backtestJobId = builder.backtestJobId;
        this.status = builder.status;
        this.reasonCode = builder.reasonCode;
        this.reasonDetail = builder.reasonDetail;
        this.stability = unmodifiableOrNull(builder.stability);
        this.retryCount = builder.retryCount;
        this.periodPerformance = unmodifiableOrNull(builder.periodPerformance);
        this.validationLabel = builder.validationLabel;
        this.selected = builder.selected;
        this.selectionReason = unmodifiableOrNull(builder.selectionReason);
    }

    /**
     * 방어 복사. <b>{@code Map.copyOf}를 쓰지 않는다</b> — 그것은 null 값을 거부한다.
     *
     * <p>여기 담기는 맵은 <b>값이 없다는 사실 자체가 뜻</b>인 자리다. 안정성은 이웃이 없으면 {@code value}가 null이고(FR-013b), 선정 사유는
     * 계산되지 않은 후보에서 {@code code}가 null이다. 키를 지우면 화면이 "없음"과 "필드가 아예 없음"을 구분할 수 없고, 0으로 채우면 계산 불가가 "가장
     * 안정적"으로 뒤집힌다(FR-011, 원칙 VI). 그래서 null을 담을 수 있는 복사가 필요하다.
     */
    private static Map<String, Object> unmodifiableOrNull(Map<String, Object> source) {
        return source == null ? null : Collections.unmodifiableMap(new LinkedHashMap<>(source));
    }

    /**
     * 계산 대상 후보를 만든다. {@code PENDING}으로 시작한다.
     *
     * <p>{@code validationLabel}은 평가 분할이 미등록이라 {@link ValidationLabel#UNVALIDATED}로 고정된다(research
     * R5). PD-12가 확정되면 분할 등록 여부에 따라 라벨이 갈린다.
     */
    public static SearchCandidate pending(
            int candidateNo,
            String strategyId,
            ParameterCombination parameters,
            GridCoordinates gridCoordinates) {
        return newCandidate(candidateNo, strategyId, parameters, gridCoordinates)
                .status(CandidateStatus.PENDING)
                .build();
    }

    /**
     * 유효성 위반으로 계산 전에 제외된 후보를 만든다(FR-006). 생성 시점에 확정되며 {@code backtestJobId}가 없다.
     *
     * <p>계산하지 않은 후보도 사유를 남긴다(FR-020). 사유 코드와 사람이 읽는 문장을 둘 다 요구하는 이유는, 코드만으로는 화면이 "왜 빠졌는지"를 설명할 수 없기
     * 때문이다.
     */
    public static SearchCandidate excluded(
            int candidateNo,
            String strategyId,
            ParameterCombination parameters,
            GridCoordinates gridCoordinates,
            String reasonCode,
            String reasonDetail) {
        requireReason(reasonCode, reasonDetail);
        return newCandidate(candidateNo, strategyId, parameters, gridCoordinates)
                .status(CandidateStatus.EXCLUDED)
                .reasonCode(reasonCode)
                .reasonDetail(reasonDetail)
                .build();
    }

    /** 저장소에서 읽어온 상태를 복원한다. 불변식 검증 없이 값을 그대로 담는다. */
    public static Builder restore() {
        return new Builder();
    }

    /** 후보 Job을 큐에 올렸다. {@code PENDING} 또는 재시도의 {@code FAILED}에서만 허용한다. */
    public SearchCandidate markDispatched(Long backtestJobId) {
        Objects.requireNonNull(backtestJobId, "backtestJobId must not be null");
        boolean retry = status == CandidateStatus.FAILED;
        return transitionTo(CandidateStatus.DISPATCHED)
                .backtestJobId(backtestJobId)
                // 재시도는 이전 실패 사유를 지운다. 남겨 두면 성공한 후보에 실패 사유가 붙는다.
                .reasonCode(null)
                .reasonDetail(null)
                .retryCount(retry ? retryCount + 1 : retryCount)
                .build();
    }

    /** 결과 이벤트가 성공으로 도착했다. */
    public SearchCandidate markCompleted() {
        return transitionTo(CandidateStatus.COMPLETED).build();
    }

    /** 결과 이벤트가 실패로 도착했거나 자료가 부족했다. 낮은 수익으로 표시하지 않는다(FR-011). */
    public SearchCandidate markFailed(String reasonCode, String reasonDetail) {
        requireReason(reasonCode, reasonDetail);
        return transitionTo(CandidateStatus.FAILED)
                .reasonCode(reasonCode)
                .reasonDetail(reasonDetail)
                .build();
    }

    /**
     * 취소·한도 도달로 발행되지 않았다. spec의 '미실행'이다.
     *
     * <p>되돌아오지 않는 확정 상태다 — 재시도 대상이 아니다. 취소·한도로 빠진 후보를 되살리면 그 묶음의 완료 범위 표시가 뜻을 잃는다.
     */
    public SearchCandidate markSkipped(String reasonCode, String reasonDetail) {
        requireReason(reasonCode, reasonDetail);
        return transitionTo(CandidateStatus.SKIPPED)
                .reasonCode(reasonCode)
                .reasonDetail(reasonDetail)
                .build();
    }

    /**
     * 인접 설정 안정성을 붙인다(FR-013a).
     *
     * <p>이웃이 없어 계산할 수 없으면 {@code calculable=false}로 남기고 <b>0으로 채우지 않는다</b>(FR-013b, 원칙 VI). 0은 "매우
     * 안정적"이라는 뜻이 되어 계산 불가와 정반대로 읽힌다.
     */
    public SearchCandidate withStability(Map<String, Object> stability) {
        return toBuilder()
                .stability(Objects.requireNonNull(stability, "stability must not be null"))
                .build();
    }

    /** 선정 여부와 사유를 붙인다. 탈락도 어느 필터에서 탈락했는지 남긴다. */
    public SearchCandidate withSelection(boolean selected, Map<String, Object> selectionReason) {
        return toBuilder().selected(selected).selectionReason(selectionReason).build();
    }

    /** 구간별 성과 자리를 채운다. 평가 분할이 미등록인 현재는 쓰이지 않는다(research R5). */
    public SearchCandidate withPeriodPerformance(
            Map<String, Object> periodPerformance, ValidationLabel validationLabel) {
        return toBuilder()
                .periodPerformance(periodPerformance)
                .validationLabel(
                        Objects.requireNonNull(validationLabel, "validationLabel must not be null"))
                .build();
    }

    /** 재시도할 수 있는가. 실패한 후보만 대상이다(FR-024). */
    public boolean isRetryable() {
        return status == CandidateStatus.FAILED;
    }

    private Builder transitionTo(CandidateStatus next) {
        if (!status.canTransitionTo(next)) {
            throw new IllegalStateException("illegal status transition: " + status + " -> " + next);
        }
        return toBuilder().status(next);
    }

    private static Builder newCandidate(
            int candidateNo,
            String strategyId,
            ParameterCombination parameters,
            GridCoordinates gridCoordinates) {
        if (candidateNo < 1) {
            throw new IllegalArgumentException("candidateNo must be positive: " + candidateNo);
        }
        if (strategyId == null || strategyId.isBlank()) {
            throw new IllegalArgumentException("strategyId must not be blank");
        }
        if (strategyId.length() > MAX_STRATEGY_ID_LENGTH) {
            throw new IllegalArgumentException(
                    "strategyId must be at most "
                            + MAX_STRATEGY_ID_LENGTH
                            + " characters: "
                            + strategyId.length());
        }
        return new Builder()
                .candidateNo(candidateNo)
                .strategyId(strategyId)
                .parameters(Objects.requireNonNull(parameters, "parameters must not be null"))
                .gridCoordinates(
                        Objects.requireNonNull(gridCoordinates, "gridCoordinates must not be null"))
                .validationLabel(ValidationLabel.UNVALIDATED)
                .retryCount(0)
                .selected(false);
    }

    private Builder toBuilder() {
        return new Builder()
                .id(id)
                .paramSearchId(paramSearchId)
                .candidateNo(candidateNo)
                .strategyId(strategyId)
                .parameters(parameters)
                .gridCoordinates(gridCoordinates)
                .backtestJobId(backtestJobId)
                .status(status)
                .reasonCode(reasonCode)
                .reasonDetail(reasonDetail)
                .stability(stability)
                .retryCount(retryCount)
                .periodPerformance(periodPerformance)
                .validationLabel(validationLabel)
                .selected(selected)
                .selectionReason(selectionReason);
    }

    private static void requireReason(String reasonCode, String reasonDetail) {
        if (reasonCode == null || reasonCode.isBlank()) {
            throw new IllegalArgumentException("reasonCode must not be blank");
        }
        if (reasonCode.length() > MAX_REASON_CODE_LENGTH) {
            throw new IllegalArgumentException(
                    "reasonCode must be at most "
                            + MAX_REASON_CODE_LENGTH
                            + " characters: "
                            + reasonCode.length());
        }
        if (reasonDetail == null || reasonDetail.isBlank()) {
            throw new IllegalArgumentException("reasonDetail must not be blank");
        }
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

    public ParameterCombination getParameters() {
        return parameters;
    }

    public GridCoordinates getGridCoordinates() {
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

    /** 필드가 16개라 생성자 인자로는 순서를 틀리기 쉽다. 복원과 상태 전이가 모두 이 빌더를 쓴다. */
    public static final class Builder {

        private Long id;
        private Long paramSearchId;
        private int candidateNo;
        private String strategyId;
        private ParameterCombination parameters;
        private GridCoordinates gridCoordinates;
        private Long backtestJobId;
        private CandidateStatus status = CandidateStatus.PENDING;
        private String reasonCode;
        private String reasonDetail;
        private Map<String, Object> stability;
        private int retryCount;
        private Map<String, Object> periodPerformance;
        private ValidationLabel validationLabel = ValidationLabel.UNVALIDATED;
        private boolean selected;
        private Map<String, Object> selectionReason;

        public Builder id(Long id) {
            this.id = id;
            return this;
        }

        public Builder paramSearchId(Long paramSearchId) {
            this.paramSearchId = paramSearchId;
            return this;
        }

        public Builder candidateNo(int candidateNo) {
            this.candidateNo = candidateNo;
            return this;
        }

        public Builder strategyId(String strategyId) {
            this.strategyId = strategyId;
            return this;
        }

        public Builder parameters(ParameterCombination parameters) {
            this.parameters = parameters;
            return this;
        }

        public Builder gridCoordinates(GridCoordinates gridCoordinates) {
            this.gridCoordinates = gridCoordinates;
            return this;
        }

        public Builder backtestJobId(Long backtestJobId) {
            this.backtestJobId = backtestJobId;
            return this;
        }

        public Builder status(CandidateStatus status) {
            this.status = status;
            return this;
        }

        public Builder reasonCode(String reasonCode) {
            this.reasonCode = reasonCode;
            return this;
        }

        public Builder reasonDetail(String reasonDetail) {
            this.reasonDetail = reasonDetail;
            return this;
        }

        public Builder stability(Map<String, Object> stability) {
            this.stability = stability;
            return this;
        }

        public Builder retryCount(int retryCount) {
            this.retryCount = retryCount;
            return this;
        }

        public Builder periodPerformance(Map<String, Object> periodPerformance) {
            this.periodPerformance = periodPerformance;
            return this;
        }

        public Builder validationLabel(ValidationLabel validationLabel) {
            this.validationLabel = validationLabel;
            return this;
        }

        public Builder selected(boolean selected) {
            this.selected = selected;
            return this;
        }

        public Builder selectionReason(Map<String, Object> selectionReason) {
            this.selectionReason = selectionReason;
            return this;
        }

        public SearchCandidate build() {
            return new SearchCandidate(this);
        }
    }
}
