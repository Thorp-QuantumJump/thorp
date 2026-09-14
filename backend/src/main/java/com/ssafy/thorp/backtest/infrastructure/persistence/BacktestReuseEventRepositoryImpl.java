package com.ssafy.thorp.backtest.infrastructure.persistence;

import com.ssafy.thorp.backtest.application.repository.BacktestReuseEventRepository;
import com.ssafy.thorp.backtest.domain.model.BacktestReuseEvent;
import org.springframework.stereotype.Repository;

/** 재사용·합류 기록 저장. 추가만 하므로 저장소도 {@code save} 하나다. */
@Repository
public class BacktestReuseEventRepositoryImpl implements BacktestReuseEventRepository {

    private final BacktestReuseEventJpaRepository jpaRepository;

    public BacktestReuseEventRepositoryImpl(BacktestReuseEventJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    public BacktestReuseEvent save(BacktestReuseEvent event) {
        if (event.id() != null) {
            throw new IllegalArgumentException("save is for new records only: id=" + event.id());
        }
        BacktestReuseEventJpaEntity saved =
                jpaRepository.save(
                        new BacktestReuseEventJpaEntity(
                                null,
                                event.runId(),
                                event.requestId(),
                                event.kind(),
                                event.requestExecutionType(),
                                event.at()));
        return new BacktestReuseEvent(
                saved.getId(),
                saved.getRunId(),
                saved.getRequestId(),
                saved.getKind(),
                saved.getRequestExecutionType(),
                saved.getAt());
    }
}
