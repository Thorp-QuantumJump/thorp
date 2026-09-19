package com.ssafy.thorp.backtest.infrastructure.persistence;

import com.ssafy.thorp.backtest.domain.model.RulesStatus;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

interface StrategyRulesJpaRepository
        extends JpaRepository<StrategyRulesJpaEntity, StrategyRulesJpaEntity.Key> {

    List<StrategyRulesJpaEntity> findByStatusNot(RulesStatus status);

    List<StrategyRulesJpaEntity> findByStrategyIdAndStatusNot(
            String strategyId, RulesStatus status);
}
