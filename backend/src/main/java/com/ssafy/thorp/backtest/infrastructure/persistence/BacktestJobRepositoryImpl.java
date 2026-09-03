package com.ssafy.thorp.backtest.infrastructure.persistence;

import com.ssafy.thorp.backtest.application.repository.BacktestJobRepository;
import com.ssafy.thorp.backtest.domain.model.BacktestJob;
import java.util.Optional;
import org.springframework.stereotype.Repository;

@Repository
public class BacktestJobRepositoryImpl implements BacktestJobRepository {

    private final BacktestJobJpaRepository jpaRepository;

    public BacktestJobRepositoryImpl(BacktestJobJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    public BacktestJob save(BacktestJob job) {
        return BacktestJobMapper.toDomain(jpaRepository.save(BacktestJobMapper.toEntity(job)));
    }

    @Override
    public Optional<BacktestJob> findById(Long id) {
        return jpaRepository.findById(id).map(BacktestJobMapper::toDomain);
    }
}
