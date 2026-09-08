package com.ssafy.thorp.backtest.domain.model;

import com.ssafy.thorp.backtest.domain.vo.BacktestId;
import com.ssafy.thorp.backtest.domain.vo.BacktestPeriod;
import com.ssafy.thorp.backtest.domain.vo.InstrumentId;
import java.math.BigDecimal;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 백테스트 작업 단위. 순수 도메인 모델이며 JPA Entity({@code BacktestJobJpaEntity})와 분리한다.
 *
 * <p>{@code id}와 {@code backtestId}는 저장 전에는 null이다. 외부 식별자 {@code backtestId}는 저장 시점에 인프라가
 * 부여하므로(ADR-0002 D2) 도메인은 값을 바꾸는 메서드를 두지 않는다.
 */
public class BacktestJob {

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
    private final BacktestStatus status;

    private BacktestJob(
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
            BacktestStatus status) {
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
        this.status = Objects.requireNonNull(status, "status must not be null");
    }

    /** 새 Job 생성. 항상 QUEUED 상태로 시작하며 id·backtestId는 저장 전이라 null이다. */
    public static BacktestJob create(
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
        return new BacktestJob(
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
                BacktestStatus.QUEUED);
    }

    /** 저장소에서 읽어온 상태를 복원한다. 불변식 검증 없이 값을 그대로 담는다. 저장된 Job은 항상 backtestId가 있다. */
    public static BacktestJob restore(
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
            BacktestStatus status) {
        return new BacktestJob(
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
                status);
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

    /** null = 벤치마크 미지정. */
    public InstrumentId getBenchmarkInstrumentId() {
        return benchmarkInstrumentId;
    }

    public int getRandomSeed() {
        return randomSeed;
    }

    public BacktestStatus getStatus() {
        return status;
    }
}
