package com.ssafy.thorp.marketflow.infrastructure.persistence;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface MarketFlowFieldSummaryJpaRepository
        extends JpaRepository<MarketFlowFieldSummaryJpaEntity, MarketFlowFieldSummaryId> {

    @Query(
            """
            select summary from MarketFlowFieldSummaryJpaEntity summary
            where summary.id.registryVersion = :registryVersion
              and summary.id.datasetVersion = :datasetVersion
            order by summary.displayOrder
            """)
    List<MarketFlowFieldSummaryJpaEntity> findVersion(
            @Param("registryVersion") String registryVersion,
            @Param("datasetVersion") String datasetVersion);
}
