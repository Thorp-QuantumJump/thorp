package com.ssafy.thorp.backtest.infrastructure.persistence;

import com.ssafy.thorp.backtest.domain.model.BacktestRun;
import com.ssafy.thorp.backtest.domain.model.RunLifecycle;
import com.ssafy.thorp.backtest.domain.model.RunPlan;
import com.ssafy.thorp.backtest.domain.model.RunProgress;
import com.ssafy.thorp.backtest.domain.model.RunState;
import com.ssafy.thorp.backtest.domain.vo.BacktestId;
import com.ssafy.thorp.backtest.domain.vo.BacktestPeriod;
import com.ssafy.thorp.backtest.domain.vo.InstrumentId;
import java.util.Map;

/** 도메인 모델과 JPA Entity 사이의 변환. 메서드는 이 둘로 제한한다(MapStruct는 도메인 3개 초과 시 재검토). */
final class BacktestRunMapper {

    private BacktestRunMapper() {}

    static BacktestRunJpaEntity toEntity(BacktestRun run) {
        RunState state = run.getState();
        RunProgress progress = state.progress();
        BacktestRunJpaEntity entity =
                new BacktestRunJpaEntity(
                        run.getId(),
                        run.getBacktestId() == null ? null : run.getBacktestId().value(),
                        run.getStrategyId(),
                        run.getStrategyVersion(),
                        run.getParameters(),
                        run.getInstrumentIds().stream().map(InstrumentId::value).toList(),
                        run.getPeriod().startDate(),
                        run.getPeriod().endDate(),
                        run.getInitialCash(),
                        run.getPositionSizingType(),
                        run.getMaxPositionPct(),
                        run.getBenchmarkInstrumentId() == null
                                ? null
                                : run.getBenchmarkInstrumentId().value(),
                        run.getRandomSeed(),
                        state.status(),
                        state.attempt(),
                        state.currentExecutionId(),
                        state.partial(),
                        progress == null ? null : progress.stage(),
                        progress == null ? null : progress.lastTradingDate(),
                        progress == null ? null : progress.completedTradingDays(),
                        progress == null ? null : progress.totalTradingDays(),
                        state.failureCode(),
                        state.failureMessage());
        applyPlanAndLifecycle(entity, run);
        return entity;
    }

    /** 계획·전달 기록은 생성자가 아니라 별도 메서드로 넣는다(엔티티 주석 참고). */
    static void applyPlanAndLifecycle(BacktestRunJpaEntity entity, BacktestRun run) {
        RunPlan plan = run.getPlan();
        entity.applyPlan(
                plan.dedupKey(),
                plan.rulesVersion(),
                plan.costProfileVersion(),
                plan.dataVersion(),
                plan.effectiveStart(),
                plan.effectiveEnd(),
                plan.requestSpec(),
                plan.versions(),
                plan.executionType());
        RunLifecycle lifecycle = run.getLifecycle();
        entity.applyLifecycle(
                lifecycle.queuedAt(),
                lifecycle.startedAt(),
                lifecycle.finishedAt(),
                lifecycle.publishedAt(),
                lifecycle.publishCount(),
                lifecycle.dlqCount(),
                lifecycle.cancelRequestedAt(),
                lifecycle.cancelReason(),
                lifecycle.lastEventAt());
    }

    static BacktestRun toDomain(BacktestRunJpaEntity entity) {
        return BacktestRun.restore(
                entity.getId(),
                BacktestId.from(entity.getBacktestId()),
                entity.getStrategyId(),
                entity.getStrategyVersion(),
                entity.getParameters(),
                entity.getInstrumentIds().stream().map(InstrumentId::new).toList(),
                new BacktestPeriod(entity.getStartDate(), entity.getEndDate()),
                entity.getInitialCash(),
                entity.getPositionSizingType(),
                entity.getMaxPositionPct(),
                entity.getBenchmarkInstrumentId() == null
                        ? null
                        : new InstrumentId(entity.getBenchmarkInstrumentId()),
                entity.getRandomSeed(),
                toState(entity),
                toPlan(entity),
                toLifecycle(entity));
    }

    /**
     * 실효 워밍업 거래일 수는 전용 컬럼이 아니라 {@code request_spec} 안에 있다. 접수가 확정한 값을 요청 사본과 같은 자리에 두면 조회 {@code
     * config}가 "요청한 값"이 아니라 "실제로 쓴 값"을 보여 준다 — null 을 그대로 돌려주는 것보다 쓸모 있다.
     */
    private static RunPlan toPlan(BacktestRunJpaEntity entity) {
        Map<String, Object> requestSpec = entity.getRequestSpec();
        Object warmup = requestSpec == null ? null : requestSpec.get("warmupTradingDays");
        return new RunPlan(
                entity.getDedupKey(),
                entity.getRulesVersion(),
                entity.getCostProfileVersion(),
                entity.getDataVersion(),
                entity.getEffectiveStart(),
                entity.getEffectiveEnd(),
                warmup instanceof Number number ? number.intValue() : null,
                requestSpec,
                entity.getVersions(),
                entity.getExecutionType());
    }

    private static RunLifecycle toLifecycle(BacktestRunJpaEntity entity) {
        return new RunLifecycle(
                entity.getQueuedAt(),
                entity.getStartedAt(),
                entity.getFinishedAt(),
                entity.getPublishedAt(),
                entity.getPublishCount(),
                entity.getDlqCount(),
                entity.getCancelRequestedAt(),
                entity.getCancelReason(),
                entity.getLastEventAt());
    }

    /** 진행 기록은 단계가 있을 때만 만든다. 단계가 없으면 아직 STARTED를 받지 않은 실행이다. */
    private static RunState toState(BacktestRunJpaEntity entity) {
        RunProgress progress =
                entity.getProgressStage() == null
                        ? null
                        : new RunProgress(
                                entity.getProgressStage(),
                                entity.getProgressLastTradingDate(),
                                entity.getProgressCompletedDays(),
                                entity.getProgressTotalDays());
        return new RunState(
                entity.getStatus(),
                entity.getAttempt(),
                entity.getCurrentExecutionId(),
                entity.isPartial(),
                progress,
                entity.getFailureCode(),
                entity.getFailureMessage());
    }
}
