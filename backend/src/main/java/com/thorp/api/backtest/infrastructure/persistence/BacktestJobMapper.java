package com.thorp.api.backtest.infrastructure.persistence;

import com.thorp.api.backtest.domain.model.BacktestJob;
import com.thorp.api.backtest.domain.vo.BacktestPeriod;

/** 도메인 모델 ↔ JPA Entity 변환. 메서드는 이 둘로 제한한다(MapStruct는 도메인 3개 초과 시 재검토). */
final class BacktestJobMapper {

    private BacktestJobMapper() {}

    static BacktestJobJpaEntity toEntity(BacktestJob job) {
        return new BacktestJobJpaEntity(
                job.getId(),
                job.getStrategy(),
                job.getPeriod().startDate(),
                job.getPeriod().endDate(),
                job.getStatus());
    }

    static BacktestJob toDomain(BacktestJobJpaEntity entity) {
        return BacktestJob.restore(
                entity.getId(),
                entity.getStrategy(),
                new BacktestPeriod(entity.getStartDate(), entity.getEndDate()),
                entity.getStatus());
    }
}
