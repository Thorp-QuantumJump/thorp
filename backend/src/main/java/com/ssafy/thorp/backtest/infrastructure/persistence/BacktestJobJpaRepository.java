package com.ssafy.thorp.backtest.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

interface BacktestJobJpaRepository extends JpaRepository<BacktestJobJpaEntity, Long> {}
