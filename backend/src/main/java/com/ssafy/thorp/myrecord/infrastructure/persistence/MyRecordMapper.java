package com.ssafy.thorp.myrecord.infrastructure.persistence;

import com.ssafy.thorp.myrecord.domain.model.MyRecord;
import com.ssafy.thorp.myrecord.domain.model.RecordRef;
import com.ssafy.thorp.myrecord.domain.vo.AnalysisPeriod;
import com.ssafy.thorp.myrecord.domain.vo.RecordId;
import com.ssafy.thorp.myrecord.domain.vo.RecordName;
import java.util.List;

/** 도메인 ↔ JPA 엔티티 변환. 종류별 참조를 세 컬럼으로 펴고 되돌린다. */
final class MyRecordMapper {

    private MyRecordMapper() {}

    static MyRecordJpaEntity toEntity(MyRecord record) {
        Long runId = null;
        Long paramSearchId = null;
        Long regimeLogId = null;
        switch (record.ref()) {
            case RecordRef.Analysis analysis -> runId = analysis.runId();
            case RecordRef.SearchBundle bundle -> paramSearchId = bundle.paramSearchId();
            case RecordRef.RegimeSearch regime -> regimeLogId = regime.logId();
        }
        AnalysisPeriod period = record.period();
        return new MyRecordJpaEntity(
                record.ownerId(),
                record.kind(),
                runId,
                paramSearchId,
                regimeLogId,
                record.name().value(),
                record.sectorCodes().toArray(String[]::new),
                record.strategyIds().toArray(String[]::new),
                period == null ? null : period.start(),
                period == null ? null : period.end(),
                record.savedAt(),
                record.lastViewedAt(),
                record.deletedAt(),
                record.isCopy());
    }

    static MyRecord toDomain(MyRecordJpaEntity entity) {
        return new MyRecord(
                entity.getId(),
                RecordId.from(entity.getRecordId()),
                entity.getOwnerId(),
                refOf(entity),
                RecordName.of(entity.getName()),
                List.of(entity.getSectorCodes()),
                List.of(entity.getStrategyIds()),
                AnalysisPeriod.ofNullable(entity.getPeriodStart(), entity.getPeriodEnd()),
                entity.getSavedAt(),
                entity.getLastViewedAt(),
                entity.getDeletedAt(),
                entity.isCopy());
    }

    /** 종류가 곧 어느 컬럼이 찼는지다. 스키마 제약이 그 짝을 강제하므로 여기서는 종류만 보면 된다. */
    private static RecordRef refOf(MyRecordJpaEntity entity) {
        return switch (entity.getKind()) {
            case ANALYSIS -> new RecordRef.Analysis(entity.getAnalysisRunId());
            case SEARCH_BUNDLE -> new RecordRef.SearchBundle(entity.getParamSearchId());
            case REGIME_SEARCH -> new RecordRef.RegimeSearch(entity.getRegimeLogId());
        };
    }
}
