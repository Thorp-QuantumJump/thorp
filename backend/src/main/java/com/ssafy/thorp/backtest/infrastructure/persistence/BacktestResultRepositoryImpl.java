package com.ssafy.thorp.backtest.infrastructure.persistence;

import com.ssafy.thorp.backtest.application.repository.BacktestResultRepository;
import com.ssafy.thorp.backtest.domain.model.BacktestResult;
import java.util.Optional;
import org.springframework.stereotype.Repository;

@Repository
public class BacktestResultRepositoryImpl implements BacktestResultRepository {

    private final BacktestResultJpaRepository jpaRepository;

    public BacktestResultRepositoryImpl(BacktestResultJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    public BacktestResult save(BacktestResult result) {
        return BacktestResultMapper.toDomain(
                jpaRepository.save(BacktestResultMapper.toEntity(result)));
    }

    @Override
    public Optional<BacktestResult> findByBacktestJobId(Long backtestJobId) {
        return jpaRepository.findByBacktestJobId(backtestJobId).map(BacktestResultMapper::toDomain);
    }
}
