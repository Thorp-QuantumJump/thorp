package com.ssafy.thorp.paramsearch.domain.model;

import com.ssafy.thorp.paramsearch.domain.vo.ParamSearchId;
import com.ssafy.thorp.paramsearch.domain.vo.SearchSpace;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 탐색 묶음 애그리게이트. 순수 도메인 모델이며 JPA Entity({@code ParamSearchJpaEntity})와 분리한다.
 *
 * <p>{@code id}와 {@code searchId}는 저장 전에는 null이다. 외부 식별자는 저장 시점에 인프라가 부여하므로({@code BacktestId}와 같은
 * 규약) 도메인은 그 값을 바꾸는 메서드를 두지 않는다.
 *
 * <p><b>불변 객체다.</b> 상태 전이는 새 인스턴스를 돌려준다. 묶음 상태는 후보 결과 이벤트를 받아 여러 번 갱신되므로, 제자리 변경을 허용하면 동시 수신에서 어느
 * 갱신이 남았는지 추적할 수 없다.
 *
 * <p><b>성과 숫자를 담지 않는다.</b> 후보의 수익·하락 폭·거래비용은 {@code backtest_result}가 원본이다. 이 모델이 갖는 것은 묶음 맥락에서만 뜻이
 * 있는 값(탐색 공간, 선정 규칙 스냅샷, 집계, 참조 버전)이다. 복제하면 004가 결과를 재저장할 때 두 값이 갈린다.
 *
 * <p><b>{@code selectionRule}은 접수 시점 스냅샷이며 이후 바뀌지 않는다</b>(FR-013c, 원칙 VII). 규칙을 바꾸려면 새 묶음을 만든다 —
 * 그래서 규칙을 바꾸는 메서드가 없다.
 */
public class ParamSearch {

    /** 저장 컬럼 폭과 같은 값. */
    public static final int MAX_REGISTRY_VERSION_LENGTH = 64;

    private final Long id;
    private final ParamSearchId searchId;
    private final Long ownerUserId;
    private final List<String> strategyIds;
    private final List<String> instrumentIds;
    private final String registryVersion;
    private final SearchSpace searchSpace;
    private final SearchMethod searchMethod;
    private final Integer randomSeed;
    private final SelectionRule selectionRule;
    private final Map<String, Object> evaluationSplit;
    private final Map<String, Object> limits;
    private final Map<String, Object> referenceVersions;
    private final Map<String, Object> executionPlan;
    private final Long baselineJobId;
    private final ParamSearchStatus status;
    private final StopReason stopReason;
    private final SearchCounts counts;
    private final Map<String, Object> selection;
    private final boolean dataVersionChanged;
    private final Instant startedAt;
    private final Instant finishedAt;

    private ParamSearch(Builder builder) {
        this.id = builder.id;
        this.searchId = builder.searchId;
        this.ownerUserId = builder.ownerUserId;
        this.strategyIds = List.copyOf(builder.strategyIds);
        this.instrumentIds = List.copyOf(builder.instrumentIds);
        this.registryVersion = builder.registryVersion;
        this.searchSpace = builder.searchSpace;
        this.searchMethod = builder.searchMethod;
        this.randomSeed = builder.randomSeed;
        this.selectionRule = builder.selectionRule;
        this.evaluationSplit =
                builder.evaluationSplit == null ? null : Map.copyOf(builder.evaluationSplit);
        this.limits = Map.copyOf(builder.limits);
        this.referenceVersions = Map.copyOf(builder.referenceVersions);
        // Map.copyOf를 쓰지 않는다 — 실행 조건에는 값이 null인 항목(평가 분할 버전, 기준 전략 미지정)이
        // 있고 Map.copyOf는 null 값에서 NPE를 던진다. 순서도 함께 지킨다(사람이 읽는 기록이다).
        this.executionPlan =
                Collections.unmodifiableMap(new LinkedHashMap<>(builder.executionPlan));
        this.baselineJobId = builder.baselineJobId;
        this.status = builder.status;
        this.stopReason = builder.stopReason;
        this.counts = builder.counts;
        // executionPlan과 같은 이유로 Map.copyOf를 쓰지 않는다 — 선정 결과에는 값이 null인 항목(채울 이유가
        // 없을 때의 note)이 있고 Map.copyOf는 null 값에서 NPE를 던진다. 키 순서는 계약 §3의 예시 순서다.
        this.selection =
                builder.selection == null
                        ? null
                        : Collections.unmodifiableMap(new LinkedHashMap<>(builder.selection));
        this.dataVersionChanged = builder.dataVersionChanged;
        this.startedAt = builder.startedAt;
        this.finishedAt = builder.finishedAt;
    }

    /**
     * 새 묶음 접수. 항상 {@code QUEUED}로 시작하며 id·searchId는 저장 전이라 null이다.
     *
     * <p>{@code evaluationSplit}은 PD-12 미확정이라 현재 항상 null이 들어온다. 그 결과 전 후보가 {@code
     * UNVALIDATED}다(research R5).
     */
    public static ParamSearch create(
            Long ownerUserId,
            List<String> strategyIds,
            List<String> instrumentIds,
            String registryVersion,
            SearchSpace searchSpace,
            SelectionRule selectionRule,
            Map<String, Object> evaluationSplit,
            Map<String, Object> limits,
            Map<String, Object> referenceVersions,
            Map<String, Object> executionPlan,
            SearchCounts counts) {
        requireNonBlankValues(strategyIds, "strategyIds");
        requireNonBlankValues(instrumentIds, "instrumentIds");
        requireNoDuplicates(strategyIds, "strategyIds");
        requireNoDuplicates(instrumentIds, "instrumentIds");
        requireRegistryVersion(registryVersion);
        return new Builder()
                .ownerUserId(ownerUserId)
                .strategyIds(strategyIds)
                .instrumentIds(instrumentIds)
                .registryVersion(registryVersion)
                .searchSpace(Objects.requireNonNull(searchSpace, "searchSpace must not be null"))
                .searchMethod(SearchMethod.FULL_GRID)
                .selectionRule(
                        Objects.requireNonNull(selectionRule, "selectionRule must not be null"))
                .evaluationSplit(evaluationSplit)
                .limits(Objects.requireNonNull(limits, "limits must not be null"))
                .referenceVersions(
                        Objects.requireNonNull(
                                referenceVersions, "referenceVersions must not be null"))
                .executionPlan(
                        Objects.requireNonNull(executionPlan, "executionPlan must not be null"))
                .counts(Objects.requireNonNull(counts, "counts must not be null"))
                .status(ParamSearchStatus.QUEUED)
                .build();
    }

    /** 저장소에서 읽어온 상태를 복원한다. 불변식 검증 없이 값을 그대로 담는다. */
    public static Builder restore() {
        return new Builder();
    }

    /** 실행이 시작됐다. {@code QUEUED}에서만 허용한다. 시작 시각은 실행시간 상한 판정과 실행 측정(FR-023)의 기준이다. */
    public ParamSearch markRunning(Instant startedAt) {
        return transitionTo(ParamSearchStatus.RUNNING)
                .startedAt(Objects.requireNonNull(startedAt, "startedAt must not be null"))
                .build();
    }

    /** 모든 유효 후보가 완료 또는 실패로 끝났다. */
    public ParamSearch markCompleted(Instant finishedAt) {
        return transitionTo(ParamSearchStatus.COMPLETED)
                .finishedAt(requireFinishedAt(finishedAt))
                .build();
    }

    /**
     * 취소·한도 도달로 미실행 후보가 남은 채 끝났다(FR-009). 부분 결과는 버리지 않는다 — 완료된 후보로 비교할 수 있다.
     *
     * <p>{@code stopReason}이 필수다. 어느 한도에 걸렸는지 없으면 화면이 부분 결과 안내 문장을 만들 수 없다.
     */
    public ParamSearch markPartial(StopReason stopReason, Instant finishedAt) {
        return transitionTo(ParamSearchStatus.PARTIAL)
                .stopReason(Objects.requireNonNull(stopReason, "stopReason must not be null"))
                .finishedAt(requireFinishedAt(finishedAt))
                .build();
    }

    /** 접수 후 실행 자체가 불가했다(기준 발행 실패 등). */
    public ParamSearch markFailed(Instant finishedAt) {
        return transitionTo(ParamSearchStatus.FAILED)
                .finishedAt(requireFinishedAt(finishedAt))
                .build();
    }

    /**
     * 접수 시점에 거부했다 — 상한 초과(FR-005b) 또는 유효 조합 전무(Edge Case).
     *
     * <p>거부한 묶음도 저장한다. 무엇을 왜 실행하지 않았는지가 재현 기록의 일부다(FR-020). 다만 후보는 만들지 않는다.
     */
    public ParamSearch reject(StopReason stopReason, Instant finishedAt) {
        return transitionTo(ParamSearchStatus.REJECTED)
                .stopReason(Objects.requireNonNull(stopReason, "stopReason must not be null"))
                .finishedAt(requireFinishedAt(finishedAt))
                .build();
    }

    /**
     * 멈추기로 했다 — 취소·실행시간 한도·연속 실패 한도(FR-009, 내부 계약 §4 불변식 2).
     *
     * <p><b>상태는 아직 바꾸지 않는다.</b> 이미 발행된 후보는 회수할 수 없어 완주하므로, 그때까지는 {@code RUNNING}이 사실이다. 계약도 취소 응답의
     * 상태가 아직 {@code RUNNING}일 수 있다고 정한다(contracts §5). 사유만 먼저 박아 두면 <b>발행을 막는 근거</b>와 <b>마지막 후보가
     * 끝났을 때 어느 사유로 {@code PARTIAL}이 되는지</b>가 한 값으로 해결된다.
     *
     * <p><b>먼저 적힌 사유가 이긴다.</b> 취소한 묶음이 뒤이어 실행시간 한도에도 걸리는 일은 흔한데, 그때 사유를 덮어쓰면 사용자가 누른 취소가 기록에서 사라진다.
     *
     * @throws IllegalStateException 이미 끝난 묶음일 때 — 끝난 것은 멈출 수 없다
     */
    public ParamSearch requestStop(StopReason stopReason) {
        Objects.requireNonNull(stopReason, "stopReason must not be null");
        if (status.isFinal()) {
            throw new IllegalStateException("already finished: " + status);
        }
        if (this.stopReason != null) {
            return this;
        }
        return toBuilder().stopReason(stopReason).build();
    }

    /** 멈추기로 한 묶음인가. 발행 경로가 이 값을 보고 새 후보를 내보내지 않는다(불변식 2). */
    public boolean isStopRequested() {
        return stopReason != null && !status.isFinal();
    }

    /**
     * 실패 후보 재시도로 묶음을 다시 연다(FR-024).
     *
     * <p><b>새 묶음을 만들지 않는다.</b> 참조 버전·탐색 공간·선정 규칙은 접수 시점 스냅샷 그대로다 — 재시도해 얻은 결과가 먼저 끝난 후보들과 같은 조건이어야
     * 나란히 놓을 수 있다(FR-012·FR-013c).
     *
     * <p><b>선정은 비운다.</b> 후보가 더 붙으므로 이전 선정은 이미 사실이 아니다. 안정성 필터가 묶음 내 상대 분위라 후보 하나가 늘면 분위 자체가 달라진다 —
     * 다시 끝날 때 한 번 더 계산한다(불변식 3).
     *
     * <p><b>중단 사유는 남긴다.</b> 취소로 '미실행'이 된 후보는 재시도 대상이 아니라 그대로 남으므로, 이 묶음은 다시 끝나도 여전히 부분 결과다. 사유를 지우면
     * 취소한 적 없는 묶음처럼 보이고 완료 범위 표시가 뜻을 잃는다.
     */
    public ParamSearch reopenForRetry() {
        return transitionTo(ParamSearchStatus.RUNNING).selection(null).finishedAt(null).build();
    }

    /** 집계를 갱신한다. 상태는 그대로다 — 상태 전이 판정은 별도다. */
    public ParamSearch withCounts(SearchCounts counts) {
        return toBuilder()
                .counts(Objects.requireNonNull(counts, "counts must not be null"))
                .build();
    }

    /** 동일 비중 기준 Job을 연결한다(FR-013d). 하락 폭 필터의 기준값이 여기서 나온다. */
    public ParamSearch withBaselineJobId(Long baselineJobId) {
        return toBuilder().baselineJobId(baselineJobId).build();
    }

    /** 선정 결과 스냅샷을 붙인다. 완료·부분 완료 후 1회 계산한다. */
    public ParamSearch withSelection(Map<String, Object> selection) {
        if (!status.isSelectable()) {
            throw new IllegalStateException("selection requires COMPLETED or PARTIAL: " + status);
        }
        return toBuilder().selection(selection).build();
    }

    /** 계산 중 자료가 갱신됐음을 표시한다(FR-012). 묶음은 시작 시점 참조 버전으로 끝까지 계산한다. */
    public ParamSearch markDataVersionChanged() {
        return toBuilder().dataVersionChanged(true).build();
    }

    private Builder transitionTo(ParamSearchStatus next) {
        if (!status.canTransitionTo(next)) {
            throw new IllegalStateException("illegal status transition: " + status + " -> " + next);
        }
        return toBuilder().status(next);
    }

    private static Instant requireFinishedAt(Instant finishedAt) {
        return Objects.requireNonNull(finishedAt, "finishedAt must not be null");
    }

    private Builder toBuilder() {
        return new Builder()
                .id(id)
                .searchId(searchId)
                .ownerUserId(ownerUserId)
                .strategyIds(strategyIds)
                .instrumentIds(instrumentIds)
                .registryVersion(registryVersion)
                .searchSpace(searchSpace)
                .searchMethod(searchMethod)
                .randomSeed(randomSeed)
                .selectionRule(selectionRule)
                .evaluationSplit(evaluationSplit)
                .limits(limits)
                .referenceVersions(referenceVersions)
                .executionPlan(executionPlan)
                .baselineJobId(baselineJobId)
                .status(status)
                .stopReason(stopReason)
                .counts(counts)
                .selection(selection)
                .dataVersionChanged(dataVersionChanged)
                .startedAt(startedAt)
                .finishedAt(finishedAt);
    }

    private static void requireRegistryVersion(String registryVersion) {
        if (registryVersion == null || registryVersion.isBlank()) {
            throw new IllegalArgumentException("registryVersion must not be blank");
        }
        if (registryVersion.length() > MAX_REGISTRY_VERSION_LENGTH) {
            throw new IllegalArgumentException(
                    "registryVersion must be at most "
                            + MAX_REGISTRY_VERSION_LENGTH
                            + " characters: "
                            + registryVersion.length());
        }
    }

    private static void requireNonBlankValues(List<String> values, String name) {
        if (values == null || values.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be empty");
        }
        if (values.stream().anyMatch(value -> value == null || value.isBlank())) {
            throw new IllegalArgumentException(name + " must not contain blank values");
        }
    }

    private static void requireNoDuplicates(List<String> values, String name) {
        if (new LinkedHashSet<>(values).size() != values.size()) {
            throw new IllegalArgumentException(name + " must not contain duplicates: " + values);
        }
    }

    public Long getId() {
        return id;
    }

    public ParamSearchId getSearchId() {
        return searchId;
    }

    public Long getOwnerUserId() {
        return ownerUserId;
    }

    /**
     * 이 묶음을 바꿀 수 있는 사람인지.
     *
     * <p>소유자가 없는 묶음(인증 이전에 만들어진 행)은 누구의 것도 아니므로 아무도 바꾸지 못한다 — 비어 있음을 "누구나"로 읽으면 그 행들이 전부 열린다.
     */
    public boolean ownedBy(long userId) {
        return ownerUserId != null && ownerUserId == userId;
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

    public SearchSpace getSearchSpace() {
        return searchSpace;
    }

    public SearchMethod getSearchMethod() {
        return searchMethod;
    }

    public Integer getRandomSeed() {
        return randomSeed;
    }

    public SelectionRule getSelectionRule() {
        return selectionRule;
    }

    public Map<String, Object> getEvaluationSplit() {
        return evaluationSplit;
    }

    public Map<String, Object> getLimits() {
        return limits;
    }

    public Map<String, Object> getReferenceVersions() {
        return referenceVersions;
    }

    /**
     * 접수 시점에 고정한 실행 조건 — 공통 구간·금액·비용 조건·대응표 버전·공통 워밍업·기준 전략이다.
     *
     * <p>후보와 기준을 조립할 때마다 현재 설정을 다시 읽으면 같은 묶음 안의 후보가 서로 다른 조건으로 계산된다(FR-007·FR-012). 형식은 {@code
     * ParamSearchSnapshot}이 정한다 — 도메인은 프레임워크도 application 계층도 모르므로 여기서는 {@code Map}으로 둔다.
     */
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

    public SearchCounts getCounts() {
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

    /** 필드가 20개를 넘어 생성자 인자로는 순서를 틀리기 쉽다. 복원과 상태 전이가 모두 이 빌더를 쓴다. */
    public static final class Builder {

        private Long id;
        private ParamSearchId searchId;
        private Long ownerUserId;
        private List<String> strategyIds = List.of();
        private List<String> instrumentIds = List.of();
        private String registryVersion;
        private SearchSpace searchSpace;
        private SearchMethod searchMethod = SearchMethod.FULL_GRID;
        private Integer randomSeed;
        private SelectionRule selectionRule;
        private Map<String, Object> evaluationSplit;
        private Map<String, Object> limits = Map.of();
        private Map<String, Object> referenceVersions = Map.of();
        private Map<String, Object> executionPlan = Map.of();
        private Long baselineJobId;
        private ParamSearchStatus status = ParamSearchStatus.QUEUED;
        private StopReason stopReason;
        private SearchCounts counts = SearchCounts.empty();
        private Map<String, Object> selection;
        private boolean dataVersionChanged;
        private Instant startedAt;
        private Instant finishedAt;

        public Builder id(Long id) {
            this.id = id;
            return this;
        }

        public Builder searchId(ParamSearchId searchId) {
            this.searchId = searchId;
            return this;
        }

        public Builder ownerUserId(Long ownerUserId) {
            this.ownerUserId = ownerUserId;
            return this;
        }

        public Builder strategyIds(List<String> strategyIds) {
            this.strategyIds = strategyIds;
            return this;
        }

        public Builder instrumentIds(List<String> instrumentIds) {
            this.instrumentIds = instrumentIds;
            return this;
        }

        public Builder registryVersion(String registryVersion) {
            this.registryVersion = registryVersion;
            return this;
        }

        public Builder searchSpace(SearchSpace searchSpace) {
            this.searchSpace = searchSpace;
            return this;
        }

        public Builder searchMethod(SearchMethod searchMethod) {
            this.searchMethod = searchMethod;
            return this;
        }

        public Builder randomSeed(Integer randomSeed) {
            this.randomSeed = randomSeed;
            return this;
        }

        public Builder selectionRule(SelectionRule selectionRule) {
            this.selectionRule = selectionRule;
            return this;
        }

        public Builder evaluationSplit(Map<String, Object> evaluationSplit) {
            this.evaluationSplit = evaluationSplit;
            return this;
        }

        public Builder limits(Map<String, Object> limits) {
            this.limits = limits;
            return this;
        }

        public Builder referenceVersions(Map<String, Object> referenceVersions) {
            this.referenceVersions = referenceVersions;
            return this;
        }

        public Builder executionPlan(Map<String, Object> executionPlan) {
            this.executionPlan = executionPlan == null ? Map.of() : executionPlan;
            return this;
        }

        public Builder baselineJobId(Long baselineJobId) {
            this.baselineJobId = baselineJobId;
            return this;
        }

        public Builder status(ParamSearchStatus status) {
            this.status = status;
            return this;
        }

        public Builder stopReason(StopReason stopReason) {
            this.stopReason = stopReason;
            return this;
        }

        public Builder counts(SearchCounts counts) {
            this.counts = counts;
            return this;
        }

        public Builder selection(Map<String, Object> selection) {
            this.selection = selection;
            return this;
        }

        public Builder dataVersionChanged(boolean dataVersionChanged) {
            this.dataVersionChanged = dataVersionChanged;
            return this;
        }

        public Builder startedAt(Instant startedAt) {
            this.startedAt = startedAt;
            return this;
        }

        public Builder finishedAt(Instant finishedAt) {
            this.finishedAt = finishedAt;
            return this;
        }

        public ParamSearch build() {
            return new ParamSearch(this);
        }
    }
}
