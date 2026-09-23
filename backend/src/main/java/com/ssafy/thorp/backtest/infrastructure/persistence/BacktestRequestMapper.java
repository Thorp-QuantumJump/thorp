package com.ssafy.thorp.backtest.infrastructure.persistence;

import com.ssafy.thorp.backtest.domain.model.BacktestRequest;
import com.ssafy.thorp.backtest.domain.model.PublicResultToken;
import com.ssafy.thorp.backtest.domain.vo.BacktestId;

/** 도메인 모델과 JPA Entity 사이의 변환. */
final class BacktestRequestMapper {

    private BacktestRequestMapper() {}

    static BacktestRequestJpaEntity toEntity(BacktestRequest request) {
        return new BacktestRequestJpaEntity(
                request.getId(),
                request.getBacktestId() == null ? null : request.getBacktestId().value(),
                request.getRunId(),
                request.getRequesterId(),
                request.getExecutionType(),
                request.getResultSource(),
                request.getRetryOfRequestId(),
                request.getCancelledAt(),
                request.getCancelledLastTradingDate(),
                request.isStoppedRun(),
                request.getPublicToken().value(),
                request.getPublicTokenExpiresAt());
    }

    static BacktestRequest toDomain(BacktestRequestJpaEntity entity) {
        return BacktestRequest.restore(
                entity.getId(),
                BacktestId.from(entity.getBacktestId()),
                entity.getRunId(),
                entity.getRequesterId(),
                entity.getExecutionType(),
                entity.getResultSource(),
                entity.getRetryOfRequestId(),
                entity.getCancelledAt(),
                entity.getCancelledLastTradingDate(),
                entity.isStoppedRun(),
                PublicResultToken.of(entity.getPublicToken()),
                entity.getPublicTokenExpiresAt());
    }
}
