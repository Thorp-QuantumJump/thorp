package com.ssafy.thorp.marketflow.infrastructure.persistence;

import com.ssafy.thorp.marketflow.application.repository.MarketFlowFieldSummaryRepository;
import com.ssafy.thorp.marketflow.domain.model.MarketFlowField;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** 같은 버전 조합을 불변 스냅샷으로 보관하는 분야 요약 저장소 구현. */
@Repository
public class MarketFlowFieldSummaryRepositoryImpl implements MarketFlowFieldSummaryRepository {

    private final MarketFlowFieldSummaryJpaRepository repository;

    public MarketFlowFieldSummaryRepositoryImpl(MarketFlowFieldSummaryJpaRepository repository) {
        this.repository = repository;
    }

    @Override
    @Transactional
    public void saveIfAbsent(
            String registryVersion,
            String datasetVersion,
            List<MarketFlowField> fields,
            Instant builtAt) {
        for (MarketFlowField field : fields) {
            MarketFlowFieldSummaryJpaEntity entity =
                    MarketFlowFieldSummaryMapper.toEntity(
                            registryVersion, datasetVersion, field, builtAt);
            if (!repository.existsById(entity.getId())) {
                repository.save(entity);
            }
        }
    }

    @Override
    @Transactional(readOnly = true)
    public List<MarketFlowField> findAll(String registryVersion, String datasetVersion) {
        return repository.findVersion(registryVersion, datasetVersion).stream()
                .map(MarketFlowFieldSummaryMapper::toDomain)
                .toList();
    }
}
