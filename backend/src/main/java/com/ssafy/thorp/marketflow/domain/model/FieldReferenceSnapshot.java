package com.ssafy.thorp.marketflow.domain.model;

import com.ssafy.thorp.marketflow.domain.vo.FieldId;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;

/** 008이 산출한 분야별 기준월과 지표별 최신 확보월의 한 자료 버전 스냅샷. */
public record FieldReferenceSnapshot(
        String registryVersion, String datasetVersion, List<FieldReferenceMonth> fields) {
    public FieldReferenceSnapshot {
        fields = List.copyOf(fields);
    }

    public record FieldReferenceMonth(
            FieldId fieldId,
            String displayName,
            int displayOrder,
            YearMonth referenceMonth,
            List<String> indicatorIds,
            Map<String, YearMonth> latestMonths,
            List<String> heldBackBy) {
        public FieldReferenceMonth {
            indicatorIds = List.copyOf(indicatorIds);
            latestMonths = Map.copyOf(latestMonths);
            heldBackBy = List.copyOf(heldBackBy);
        }
    }
}
