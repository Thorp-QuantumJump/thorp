package com.ssafy.thorp.backtest.infrastructure.persistence;

import com.ssafy.thorp.backtest.domain.model.BacktestRun;
import com.ssafy.thorp.backtest.domain.model.RunProgress;
import com.ssafy.thorp.backtest.domain.model.RunState;
import com.ssafy.thorp.backtest.domain.vo.BacktestId;
import com.ssafy.thorp.backtest.domain.vo.BacktestPeriod;
import com.ssafy.thorp.backtest.domain.vo.InstrumentId;

/** 도메인 모델과 JPA Entity 사이의 변환. 메서드는 이 둘로 제한한다(MapStruct는 도메인 3개 초과 시 재검토). */
final class BacktestRunMapper {

    private BacktestRunMapper() {}

    static BacktestRunJpaEntity toEntity(BacktestRun run) {
        RunState state = run.getState();
        RunProgress progress = state.progress();
        return new BacktestRunJpaEntity(
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
                toState(entity));
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
