package com.ssafy.thorp.backtest.infrastructure.persistence;

import com.ssafy.thorp.backtest.application.repository.BacktestReuseEventRepository;
import com.ssafy.thorp.backtest.domain.model.BacktestReuseEvent;
import org.springframework.stereotype.Repository;

/** 재사용·합류 기록 저장. 추가와, 만료된 비로그인 요청 정리를 위한 요청 참조 끊기만 있다. */
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

    @Override
    public int clearRequestId(long requestId) {
        return jpaRepository.clearRequestId(requestId);
    }
}
