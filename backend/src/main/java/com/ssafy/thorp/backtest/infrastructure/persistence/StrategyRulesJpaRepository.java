package com.ssafy.thorp.backtest.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

interface StrategyRulesJpaRepository
        extends JpaRepository<StrategyRulesJpaEntity, StrategyRulesJpaEntity.Key> {}
