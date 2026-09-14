package com.ssafy.thorp.backtest.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

/** 재사용·합류 기록은 추가만 하므로 기본 {@code save} 말고 필요한 것이 없다. */
interface BacktestReuseEventJpaRepository
        extends JpaRepository<BacktestReuseEventJpaEntity, Long> {}
