package com.ssafy.thorp.backtest.infrastructure.persistence;

import com.ssafy.thorp.backtest.domain.model.BacktestJob;
import com.ssafy.thorp.backtest.domain.vo.BacktestId;
import com.ssafy.thorp.backtest.domain.vo.BacktestPeriod;
import com.ssafy.thorp.backtest.domain.vo.InstrumentId;

/** 도메인 모델과 JPA Entity 사이의 변환. 메서드는 이 둘로 제한한다(MapStruct는 도메인 3개 초과 시 재검토). */
final class BacktestJobMapper {

    private BacktestJobMapper() {}

    static BacktestJobJpaEntity toEntity(BacktestJob job) {
        return new BacktestJobJpaEntity(
                job.getId(),
                job.getBacktestId() == null ? null : job.getBacktestId().value(),
                job.getStrategyId(),
                job.getStrategyVersion(),
                job.getParameters(),
                job.getInstrumentIds().stream().map(InstrumentId::value).toList(),
                job.getPeriod().startDate(),
                job.getPeriod().endDate(),
                job.getInitialCash(),
                job.getPositionSizingType(),
                job.getMaxPositionPct(),
                job.getBenchmarkInstrumentId() == null
                        ? null
                        : job.getBenchmarkInstrumentId().value(),
                job.getRandomSeed(),
                job.getStatus());
    }

    static BacktestJob toDomain(BacktestJobJpaEntity entity) {
        return BacktestJob.restore(
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
                entity.getStatus());
    }
}
