package com.ssafy.thorp.backtest.infrastructure.persistence;

import com.ssafy.thorp.backtest.application.repository.StrategyRulesRepository;
import com.ssafy.thorp.backtest.domain.model.RulesStatus;
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

    @Override
    public List<StrategyRules> findCurrent() {
        return jpaRepository.findByStatusNot(RulesStatus.SUPERSEDED).stream()
                .map(StrategyRulesRepositoryImpl::toDomain)
                .toList();
    }

    @Override
    public Optional<StrategyRules> findCurrent(String strategyId) {
        // 부분 유니크 인덱스가 전략당 한 행을 보장한다. 둘이 나오면 스키마가 깨진 것이라 조용히 하나를 고르지 않는다.
        List<StrategyRulesJpaEntity> rows =
                jpaRepository.findByStrategyIdAndStatusNot(strategyId, RulesStatus.SUPERSEDED);
        if (rows.size() > 1) {
            throw new IllegalStateException(
                    "strategy_rules has more than one current row: " + strategyId);
        }
        return rows.stream().findFirst().map(StrategyRulesRepositoryImpl::toDomain);
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
