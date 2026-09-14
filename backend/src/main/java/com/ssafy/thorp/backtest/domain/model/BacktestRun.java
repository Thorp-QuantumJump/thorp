package com.ssafy.thorp.backtest.domain.model;

import com.ssafy.thorp.backtest.domain.vo.BacktestId;
import com.ssafy.thorp.backtest.domain.vo.BacktestPeriod;
import com.ssafy.thorp.backtest.domain.vo.InstrumentId;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 백테스트 실행 한 건. 순수 도메인 모델이며 JPA Entity({@code BacktestRunJpaEntity})와 분리한다.
 *
 * <p>여러 <b>요청</b>({@link BacktestRequest})이 같은 실행을 가리킬 수 있다(spec 004 Clarify Q2·Q3). 동일 버전 요청은 새
 * 계산을 만들지 않고 진행 중인 실행에 합류하거나 완료된 결과를 재사용한다. 그 판정과 요청 생성은 접수 유스케이스(T005)의 몫이고, 이 모델은 실행 하나의 상태 전이만
 * 책임진다.
 *
 * <p>{@code id}와 {@code backtestId}는 저장 전에는 null이다. 외부 식별자 {@code backtestId}는 저장 시점에 인프라가
 * 부여하므로(ADR-0002 D2) 도메인은 값을 바꾸는 메서드를 두지 않는다. 이 식별자는 T005에서 요청 쪽으로 옮겨간다.
 */
public class BacktestRun {

    /** 저장 컬럼 폭과 같은 값. 요청 DTO와 JPA 컬럼이 이 상수를 함께 쓴다. */
    public static final int MAX_STRATEGY_ID_LENGTH = 64;

    private final Long id;
    private final BacktestId backtestId;
    private final String strategyId;
    private final String strategyVersion;
    private final Map<String, Object> parameters;
    private final List<InstrumentId> instrumentIds;
    private final BacktestPeriod period;
    private final BigDecimal initialCash;
    private final PositionSizingMode positionSizingType;
    private final BigDecimal maxPositionPct;
    private final InstrumentId benchmarkInstrumentId;
    private final int randomSeed;
    private final RunState state;
    private final RunPlan plan;
    private final RunLifecycle lifecycle;

    private BacktestRun(
            Long id,
            BacktestId backtestId,
            String strategyId,
            String strategyVersion,
            Map<String, Object> parameters,
            List<InstrumentId> instrumentIds,
            BacktestPeriod period,
            BigDecimal initialCash,
            PositionSizingMode positionSizingType,
            BigDecimal maxPositionPct,
            InstrumentId benchmarkInstrumentId,
            int randomSeed,
            RunState state,
            RunPlan plan,
            RunLifecycle lifecycle) {
        this.id = id;
        this.backtestId = backtestId;
        this.strategyId = Objects.requireNonNull(strategyId, "strategyId must not be null");
        this.strategyVersion =
                Objects.requireNonNull(strategyVersion, "strategyVersion must not be null");
        this.parameters =
                Map.copyOf(Objects.requireNonNull(parameters, "parameters must not be null"));
        this.instrumentIds =
                List.copyOf(
                        Objects.requireNonNull(instrumentIds, "instrumentIds must not be null"));
        this.period = Objects.requireNonNull(period, "period must not be null");
        this.initialCash = Objects.requireNonNull(initialCash, "initialCash must not be null");
        this.positionSizingType =
                Objects.requireNonNull(positionSizingType, "positionSizingType must not be null");
        this.maxPositionPct = maxPositionPct;
        this.benchmarkInstrumentId = benchmarkInstrumentId;
        this.randomSeed = randomSeed;
        this.state = Objects.requireNonNull(state, "state must not be null");
        this.plan = Objects.requireNonNullElseGet(plan, RunPlan::empty);
        this.lifecycle =
                Objects.requireNonNullElseGet(
                        lifecycle,
                        () -> new RunLifecycle(null, null, null, null, 0, 0, null, null, null));
    }

    /** 새 실행 생성. 항상 QUEUED로 시작하며 id·backtestId는 저장 전이라 null이다. */
    public static BacktestRun create(
            String strategyId,
            String strategyVersion,
            Map<String, Object> parameters,
            List<InstrumentId> instrumentIds,
            BacktestPeriod period,
            BigDecimal initialCash,
            PositionSizingMode positionSizingType,
            BigDecimal maxPositionPct,
            InstrumentId benchmarkInstrumentId,
            int randomSeed) {
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
        if (strategyVersion == null || strategyVersion.isBlank()) {
            throw new IllegalArgumentException("strategyVersion must not be blank");
        }
        if (parameters == null) {
            throw new IllegalArgumentException("parameters must not be null");
        }
        if (parameters.values().stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException(
                    "parameters must not contain null values: " + parameters.keySet());
        }
        if (instrumentIds == null || instrumentIds.isEmpty()) {
            throw new IllegalArgumentException("instrumentIds must not be empty");
        }
        if (new LinkedHashSet<>(instrumentIds).size() != instrumentIds.size()) {
            throw new IllegalArgumentException(
                    "instrumentIds must not contain duplicates: " + instrumentIds);
        }
        if (initialCash == null || initialCash.signum() <= 0) {
            throw new IllegalArgumentException("initialCash must be positive: " + initialCash);
        }
        if (maxPositionPct != null
                && (maxPositionPct.signum() <= 0 || maxPositionPct.compareTo(BigDecimal.ONE) > 0)) {
            throw new IllegalArgumentException(
                    "maxPositionPct must be in (0, 1]: " + maxPositionPct);
        }
        return new BacktestRun(
                null,
                null,
                strategyId,
                strategyVersion,
                parameters,
                instrumentIds,
                period,
                initialCash,
                positionSizingType,
                maxPositionPct,
                benchmarkInstrumentId,
                randomSeed,
                RunState.of(RunStatus.QUEUED),
                RunPlan.empty(),
                new RunLifecycle(null, null, null, null, 0, 0, null, null, null));
    }

    /** 상태만 아는 복원. 실행 기록(시도·진행·실패)이 필요 없는 호출자용이다. */
    public static BacktestRun restore(
            Long id,
            BacktestId backtestId,
            String strategyId,
            String strategyVersion,
            Map<String, Object> parameters,
            List<InstrumentId> instrumentIds,
            BacktestPeriod period,
            BigDecimal initialCash,
            PositionSizingMode positionSizingType,
            BigDecimal maxPositionPct,
            InstrumentId benchmarkInstrumentId,
            int randomSeed,
            RunStatus status) {
        return restore(
                id,
                backtestId,
                strategyId,
                strategyVersion,
                parameters,
                instrumentIds,
                period,
                initialCash,
                positionSizingType,
                maxPositionPct,
                benchmarkInstrumentId,
                randomSeed,
                RunState.of(status));
    }

    /** 접수 경로가 채운 계획·전달 기록까지 복원한다. 저장소 구현이 쓰는 완전한 형태다. */
    public static BacktestRun restore(
            Long id,
            BacktestId backtestId,
            String strategyId,
            String strategyVersion,
            Map<String, Object> parameters,
            List<InstrumentId> instrumentIds,
            BacktestPeriod period,
            BigDecimal initialCash,
            PositionSizingMode positionSizingType,
            BigDecimal maxPositionPct,
            InstrumentId benchmarkInstrumentId,
            int randomSeed,
            RunState state,
            RunPlan plan,
            RunLifecycle lifecycle) {
        return new BacktestRun(
                id,
                backtestId,
                strategyId,
                strategyVersion,
                parameters,
                instrumentIds,
                period,
                initialCash,
                positionSizingType,
                maxPositionPct,
                benchmarkInstrumentId,
                randomSeed,
                state,
                plan,
                lifecycle);
    }

    /** 저장소에서 읽어온 상태를 복원한다. 불변식 검증 없이 값을 그대로 담는다. 저장된 실행은 항상 backtestId가 있다. */
    public static BacktestRun restore(
            Long id,
            BacktestId backtestId,
            String strategyId,
            String strategyVersion,
            Map<String, Object> parameters,
            List<InstrumentId> instrumentIds,
            BacktestPeriod period,
            BigDecimal initialCash,
            PositionSizingMode positionSizingType,
            BigDecimal maxPositionPct,
            InstrumentId benchmarkInstrumentId,
            int randomSeed,
            RunState state) {
        return restore(
                id,
                backtestId,
                strategyId,
                strategyVersion,
                parameters,
                instrumentIds,
                period,
                initialCash,
                positionSizingType,
                maxPositionPct,
                benchmarkInstrumentId,
                randomSeed,
                state,
                RunPlan.empty(),
                new RunLifecycle(null, null, null, null, 0, 0, null, null, null));
    }

    /**
     * 워커가 실행을 시작했다(STARTED). 시도 횟수를 올리고 마지막 실행 시도 식별자를 기록한다.
     *
     * <p>QUEUED면 RUNNING으로 전이한다. 이미 RUNNING인데 <b>다른</b> executionId의 STARTED가 오면 상태는 그대로 두고 시도만 올린다
     * — 워커가 죽어 브로커가 재전달한 자동 재시도다(R-06). 같은 executionId의 중복 STARTED는 아무것도 바꾸지 않는다.
     */
    public BacktestRun markRunning(String executionId) {
        if (executionId == null || executionId.isBlank()) {
            throw new IllegalArgumentException("executionId must not be blank");
        }
        if (state.status().isFinal()) {
            throw new IllegalStateException(
                    "illegal status transition: " + state.status() + " -> RUNNING");
        }
        if (executionId.equals(state.currentExecutionId())) {
            return this;
        }
        return withState(
                new RunState(
                        RunStatus.RUNNING,
                        state.attempt() + 1,
                        executionId,
                        false,
                        RunProgress.preparingData(),
                        null,
                        null));
    }

    /** 계산 단계의 진행을 기록한다(R-05). 최종 상태에서는 무시하지 않고 거부한다 — 호출자가 상태로 먼저 걸러낸다. */
    public BacktestRun recordProgress(RunProgress progress) {
        Objects.requireNonNull(progress, "progress must not be null");
        if (state.status() != RunStatus.RUNNING) {
            throw new IllegalStateException("progress is only for RUNNING runs: " + state.status());
        }
        return withState(
                new RunState(
                        state.status(),
                        state.attempt(),
                        state.currentExecutionId(),
                        false,
                        progress,
                        null,
                        null));
    }

    /**
     * 실행이 성공으로 끝났다(FINISHED, COMPLETED). 최종 상태가 아니면 허용한다.
     *
     * <p>RUNNING으로 제한하지 않는다. STARTED가 유실되면 FINISHED가 QUEUED 상태로 도착하는데, 그때 거부하면 결과가 있는데도 실행이 영원히
     * QUEUED다. FINISHED는 실행의 최종 결과이므로 STARTED 수신 여부와 무관하게 받는다.
     */
    public BacktestRun markCompleted() {
        requireNotFinal(RunStatus.COMPLETED);
        return withState(
                new RunState(
                        RunStatus.COMPLETED,
                        state.attempt(),
                        state.currentExecutionId(),
                        false,
                        state.progress(),
                        null,
                        null));
    }

    /** 실행이 실패로 끝났다. 사유 코드는 계약의 {@code run.error.code} 또는 시스템 장애(SYSTEM_FAILURE)다. */
    public BacktestRun markFailed(String failureCode, String failureMessage) {
        requireNotFinal(RunStatus.FAILED);
        if (failureCode == null || failureCode.isBlank()) {
            throw new IllegalArgumentException("failureCode must not be blank");
        }
        return withState(
                new RunState(
                        RunStatus.FAILED,
                        state.attempt(),
                        state.currentExecutionId(),
                        false,
                        state.progress(),
                        failureCode,
                        failureMessage));
    }

    /**
     * 취소로 끝났다. 부분 결과가 붙으므로 {@code partial}이 true다(Clarify Q1).
     *
     * <p>{@code lastTradingDate}는 워커가 실제로 멈춘 거래일이다. 백엔드가 마지막으로 받은 PROGRESS보다 며칠 뒤일 수 있으므로 진행 기록을 그
     * 값으로 갱신한다(R-04).
     */
    public BacktestRun markCancelled(LocalDate lastTradingDate) {
        requireNotFinal(RunStatus.CANCELLED);
        RunProgress stopped =
                lastTradingDate == null
                        ? state.progress()
                        : new RunProgress(
                                ProgressStage.COMPUTING,
                                lastTradingDate,
                                state.progress() == null
                                        ? null
                                        : state.progress().completedTradingDays(),
                                state.progress() == null
                                        ? null
                                        : state.progress().totalTradingDays());
        return withState(
                new RunState(
                        RunStatus.CANCELLED,
                        state.attempt(),
                        state.currentExecutionId(),
                        true,
                        stopped,
                        null,
                        null));
    }

    private void requireNotFinal(RunStatus next) {
        if (state.status().isFinal()) {
            throw new IllegalStateException(
                    "illegal status transition: " + state.status() + " -> " + next);
        }
    }

    /** 불변 객체이므로 상태만 바뀐 새 인스턴스를 돌려준다. */
    private BacktestRun withState(RunState next) {
        return new BacktestRun(
                id,
                backtestId,
                strategyId,
                strategyVersion,
                parameters,
                instrumentIds,
                period,
                initialCash,
                positionSizingType,
                maxPositionPct,
                benchmarkInstrumentId,
                randomSeed,
                next,
                plan,
                lifecycle);
    }

    /** 접수가 확정한 계획을 붙인다. 저장 직전 한 번만 호출한다 — 그 뒤로 바뀌면 재현이 깨진다. */
    public BacktestRun withPlan(RunPlan next) {
        return new BacktestRun(
                id,
                backtestId,
                strategyId,
                strategyVersion,
                parameters,
                instrumentIds,
                period,
                initialCash,
                positionSizingType,
                maxPositionPct,
                benchmarkInstrumentId,
                randomSeed,
                state,
                Objects.requireNonNull(next, "plan must not be null"),
                lifecycle);
    }

    /** 발행 시도·confirm·DLQ·시각 기록을 갱신한다(R-18). 상태 전이와는 독립이다. */
    public BacktestRun withLifecycle(RunLifecycle next) {
        return new BacktestRun(
                id,
                backtestId,
                strategyId,
                strategyVersion,
                parameters,
                instrumentIds,
                period,
                initialCash,
                positionSizingType,
                maxPositionPct,
                benchmarkInstrumentId,
                randomSeed,
                state,
                plan,
                Objects.requireNonNull(next, "lifecycle must not be null"));
    }

    public Long getId() {
        return id;
    }

    /** 외부 식별자. 저장 전에는 null이다. */
    public BacktestId getBacktestId() {
        return backtestId;
    }

    public String getStrategyId() {
        return strategyId;
    }

    public String getStrategyVersion() {
        return strategyVersion;
    }

    public Map<String, Object> getParameters() {
        return parameters;
    }

    public List<InstrumentId> getInstrumentIds() {
        return instrumentIds;
    }

    public BacktestPeriod getPeriod() {
        return period;
    }

    public BigDecimal getInitialCash() {
        return initialCash;
    }

    public PositionSizingMode getPositionSizingType() {
        return positionSizingType;
    }

    /** null = 제한 없음. */
    public BigDecimal getMaxPositionPct() {
        return maxPositionPct;
    }

    /** null = 벤치마크 미지정. 메시지 계약 v2에는 벤치마크가 없어 실행에 실리지 않는다. */
    public InstrumentId getBenchmarkInstrumentId() {
        return benchmarkInstrumentId;
    }

    public int getRandomSeed() {
        return randomSeed;
    }

    public RunState getState() {
        return state;
    }

    public RunStatus getStatus() {
        return state.status();
    }

    public int getAttempt() {
        return state.attempt();
    }

    /** null = 아직 STARTED를 받지 않았다. */
    public String getCurrentExecutionId() {
        return state.currentExecutionId();
    }

    /** true = 취소·한도로 끊긴 부분 결과. */
    public boolean isPartial() {
        return state.partial();
    }

    /** null = 아직 진행 기록이 없다. */
    public RunProgress getProgress() {
        return state.progress();
    }

    public String getFailureCode() {
        return state.failureCode();
    }

    public String getFailureMessage() {
        return state.failureMessage();
    }

    /** 접수가 확정한 값(동일 버전 키·실효 기간·버전 묶음). 접수 경로를 거치지 않은 실행은 비어 있다. */
    public RunPlan getPlan() {
        return plan;
    }

    public RunLifecycle getLifecycle() {
        return lifecycle;
    }

    /** null = 접수 경로를 거치지 않았다. 재사용 판정에 걸리지 않는다. */
    public String getDedupKey() {
        return plan.dedupKey();
    }
}
