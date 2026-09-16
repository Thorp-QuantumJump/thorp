package com.ssafy.thorp.marketflow.infrastructure.persistence;

import com.ssafy.thorp.marketflow.domain.model.MarketFlowField;
import com.ssafy.thorp.marketflow.domain.vo.FieldId;
import java.time.Instant;
import java.time.YearMonth;
import java.util.List;

final class MarketFlowFieldSummaryMapper {

    private MarketFlowFieldSummaryMapper() {}

    static MarketFlowFieldSummaryJpaEntity toEntity(
            String registryVersion, String datasetVersion, MarketFlowField field, Instant builtAt) {
        return new MarketFlowFieldSummaryJpaEntity(
                new MarketFlowFieldSummaryId(
                        field.fieldId().value(), registryVersion, datasetVersion),
                field.displayName(),
                field.displayOrder(),
                field.narrative(),
                field.narrativeRuleVersion(),
                field.narrativeSubjects(),
                field.status(),
                field.exampleData(),
                field.referenceMonth() == null ? null : field.referenceMonth().toString(),
                field.referenceMonthHeldBackBy(),
                field.lastUpdatedAt(),
                builtAt);
    }

    static MarketFlowField toDomain(MarketFlowFieldSummaryJpaEntity entity) {
        return new MarketFlowField(
                FieldId.from(entity.getId().getFieldId()),
                entity.getDisplayName(),
                entity.getDisplayOrder(),
                entity.getStatus(),
                entity.getNarrative(),
                entity.getNarrativeRuleVersion(),
                entity.getNarrativeSubjects(),
                entity.getReferenceMonth() == null
                        ? null
                        : YearMonth.parse(entity.getReferenceMonth()),
                entity.getReferenceMonthHeldBackBy(),
                entity.getLastUpdatedAt(),
                entity.isExampleData(),
                List.of());
    }
}
