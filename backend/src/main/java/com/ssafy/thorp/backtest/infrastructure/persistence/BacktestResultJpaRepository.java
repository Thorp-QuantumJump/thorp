package com.ssafy.thorp.backtest.infrastructure.persistence;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

interface BacktestResultJpaRepository extends JpaRepository<BacktestResultJpaEntity, Long> {

    Optional<BacktestResultJpaEntity> findByBacktestJobId(Long backtestJobId);
}
