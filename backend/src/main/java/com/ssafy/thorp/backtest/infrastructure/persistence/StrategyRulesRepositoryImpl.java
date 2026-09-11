package com.ssafy.thorp.backtest.infrastructure.persistence;

import com.ssafy.thorp.backtest.application.repository.StrategyRulesRepository;
import com.ssafy.thorp.backtest.domain.model.StrategyRules;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/** 읽기 전용 등록표라 조회만 노출한다. 행 추가·갱신은 마이그레이션이 한다. */
@Repository
public class StrategyRulesRepositoryImpl implements StrategyRulesRepository {

    private final StrategyRulesJpaRepository jpaRepository;

    public StrategyRulesRepositoryImpl(StrategyRulesJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    public Optional<StrategyRules> find(String strategyId, String rulesVersion) {
        return jpaRepository
                .findById(new StrategyRulesJpaEntity.Key(strategyId, rulesVersion))
                .map(StrategyRulesRepositoryImpl::toDomain);
    }

    @Override
    public List<StrategyRules> findAll() {
        return jpaRepository.findAll().stream().map(StrategyRulesRepositoryImpl::toDomain).toList();
    }

    private static StrategyRules toDomain(StrategyRulesJpaEntity entity) {
        return new StrategyRules(
                entity.getStrategyId(),
                entity.getRulesVersion(),
                entity.getStatus(),
                entity.isProvisional(),
                entity.getSpec(),
                entity.getDecisionRef());
    }
}
