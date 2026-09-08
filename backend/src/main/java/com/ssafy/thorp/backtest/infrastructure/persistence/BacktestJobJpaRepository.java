package com.ssafy.thorp.backtest.infrastructure.persistence;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

interface BacktestJobJpaRepository extends JpaRepository<BacktestJobJpaEntity, Long> {

    Optional<BacktestJobJpaEntity> findByBacktestId(String backtestId);
}
