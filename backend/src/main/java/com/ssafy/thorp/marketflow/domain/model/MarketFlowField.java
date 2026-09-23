package com.ssafy.thorp.marketflow.domain.model;

import com.ssafy.thorp.marketflow.domain.vo.FieldId;
import com.ssafy.thorp.marketflow.domain.vo.FieldStatus;
import java.time.Instant;
import java.time.YearMonth;
import java.util.List;

/** 한 시장 흐름 분야의 읽기 모델. 경제 전체 점수·등급·신호 필드는 의도적으로 없다. */
public record MarketFlowField(
        FieldId fieldId,
        String displayName,
        int displayOrder,
        FieldStatus status,
        String narrative,
        String narrativeRuleVersion,
        List<String> narrativeSubjects,
        YearMonth referenceMonth,
        List<String> referenceMonthHeldBackBy,
        Instant lastUpdatedAt,
        boolean exampleData,
        List<IndicatorDisplay> indicators) {

    public MarketFlowField {
        if (fieldId == null || status == null) {
            throw new IllegalArgumentException("fieldId와 status는 필수입니다.");
        }
        if (displayName == null || displayName.isBlank() || displayOrder < 1) {
            throw new IllegalArgumentException("표시 이름과 순서는 필수입니다.");
        }
        narrativeSubjects = List.copyOf(narrativeSubjects);
        referenceMonthHeldBackBy = List.copyOf(referenceMonthHeldBackBy);
        indicators = List.copyOf(indicators);
        if ((status == FieldStatus.EMPTY || status == FieldStatus.COLLECTION_FAILED)
                && narrative != null) {
            throw new IllegalArgumentException("자료 없음·수집 실패 분야는 요약 문장을 만들지 않습니다.");
        }
        if (exampleData && status != FieldStatus.EXAMPLE) {
            throw new IllegalArgumentException("예시 자료 상태는 EXAMPLE이어야 합니다.");
        }
        if (status == FieldStatus.READY && (referenceMonth == null || lastUpdatedAt == null)) {
            throw new IllegalArgumentException("READY 분야에는 기준월과 갱신 시각이 필요합니다.");
        }
    }
}
