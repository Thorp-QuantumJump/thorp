package com.ssafy.thorp.backtest.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

interface BacktestResultJpaRepository extends JpaRepository<BacktestResultJpaEntity, Long> {}
