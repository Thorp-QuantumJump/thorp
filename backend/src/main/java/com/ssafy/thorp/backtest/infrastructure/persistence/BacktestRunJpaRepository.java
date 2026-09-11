package com.ssafy.thorp.backtest.infrastructure.persistence;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

interface BacktestRunJpaRepository extends JpaRepository<BacktestRunJpaEntity, Long> {

    Optional<BacktestRunJpaEntity> findByBacktestId(String backtestId);
}
