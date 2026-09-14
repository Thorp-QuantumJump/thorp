package com.ssafy.thorp.backtest.infrastructure.persistence;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

interface BacktestRequestJpaRepository extends JpaRepository<BacktestRequestJpaEntity, Long> {

    Optional<BacktestRequestJpaEntity> findByBacktestId(String backtestId);

    List<BacktestRequestJpaEntity> findByRunId(Long runId);

    long countByRunIdAndCancelledAtIsNull(Long runId);
}
