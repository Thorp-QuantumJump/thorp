package com.ssafy.thorp.marketflow.presentation.response;

import com.ssafy.thorp.marketflow.domain.model.MarketFlowField;
import com.ssafy.thorp.marketflow.domain.vo.FieldStatus;
import java.time.Instant;
import java.time.YearMonth;
import java.util.List;

/** 시장 흐름 기본 화면의 분야 한 칸. null 필드도 계약에 명시적으로 남긴다. */
public record FieldSummaryResponse(
        String fieldId,
        String displayName,
        int displayOrder,
        FieldStatus status,
        String narrative,
        List<String> narrativeSubjects,
        YearMonth referenceMonth,
        List<String> referenceMonthHeldBackBy,
        Instant lastUpdatedAt,
        boolean isExampleData) {

    public static FieldSummaryResponse from(MarketFlowField field) {
        return new FieldSummaryResponse(
                field.fieldId().value(),
                field.displayName(),
                field.displayOrder(),
                field.status(),
                field.narrative(),
                field.narrativeSubjects(),
                field.referenceMonth(),
                field.referenceMonthHeldBackBy(),
                field.lastUpdatedAt(),
                field.exampleData());
    }
}
