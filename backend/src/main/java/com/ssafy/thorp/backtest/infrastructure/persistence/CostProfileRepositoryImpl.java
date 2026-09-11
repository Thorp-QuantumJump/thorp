package com.ssafy.thorp.backtest.infrastructure.persistence;

import com.ssafy.thorp.backtest.application.repository.CostProfileRepository;
import com.ssafy.thorp.backtest.domain.model.CostProfile;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/** 읽기 전용 등록표라 조회만 노출한다. 행 추가·갱신은 마이그레이션이 한다. */
@Repository
public class CostProfileRepositoryImpl implements CostProfileRepository {

    private final CostProfileJpaRepository jpaRepository;

    public CostProfileRepositoryImpl(CostProfileJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    public Optional<CostProfile> find(String version) {
        return jpaRepository.findById(version).map(CostProfileRepositoryImpl::toDomain);
    }

    private static CostProfile toDomain(CostProfileJpaEntity entity) {
        return new CostProfile(
                entity.getVersion(),
                entity.getCommissionBps(),
                entity.getSpreadBps(),
                entity.getSlippageBps(),
                entity.getTaxBps(),
                entity.getEscalation(),
                entity.isProvisional(),
                entity.getNote());
    }
}
