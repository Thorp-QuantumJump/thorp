package com.ssafy.thorp.marketflow.presentation.response;

import com.ssafy.thorp.marketflow.domain.model.AfterReferenceMonthRelease;
import com.ssafy.thorp.marketflow.domain.model.IndicatorDisplay;
import com.ssafy.thorp.marketflow.domain.model.MarketFlowField;
import com.ssafy.thorp.marketflow.domain.vo.FieldStatus;
import com.ssafy.thorp.marketflow.domain.vo.PathPoint;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

/** 한 분야의 요약과 지표 실제 값·최근 경로를 함께 보내는 응답. */
public record MarketFlowFieldDetailResponse(
        String fieldId,
        String displayName,
        int displayOrder,
        FieldStatus status,
        String narrative,
        List<String> narrativeSubjects,
        YearMonth referenceMonth,
        List<String> referenceMonthHeldBackBy,
        Instant lastUpdatedAt,
        boolean isExampleData,
        String multipleReferenceMonthNotice,
        List<IndicatorResponse> indicators) {

    public static final String MULTIPLE_REFERENCE_MONTH_NOTICE = "분야마다 자료가 갖춰진 기준월이 다를 수 있습니다.";

    public static MarketFlowFieldDetailResponse from(MarketFlowField field) {
        return new MarketFlowFieldDetailResponse(
                field.fieldId().value(),
                field.displayName(),
                field.displayOrder(),
                field.status(),
                field.narrative(),
                field.narrativeSubjects(),
                field.referenceMonth(),
                field.referenceMonthHeldBackBy(),
                field.lastUpdatedAt(),
                field.exampleData(),
                MULTIPLE_REFERENCE_MONTH_NOTICE,
                field.indicators().stream().map(IndicatorResponse::from).toList());
    }

    public record IndicatorResponse(
            String indicatorId,
            String name,
            String unit,
            BigDecimal value,
            List<PathPointResponse> path,
            YearMonth referenceMonth,
            LocalDate releaseDate,
            String source,
            String agency,
            AfterReferenceMonthReleaseResponse afterReferenceMonthRelease) {

        static IndicatorResponse from(IndicatorDisplay indicator) {
            return new IndicatorResponse(
                    indicator.indicatorId(),
                    indicator.name(),
                    indicator.unit(),
                    indicator.value(),
                    indicator.path().stream().map(PathPointResponse::from).toList(),
                    indicator.referenceMonth(),
                    indicator.releaseDate(),
                    indicator.source(),
                    indicator.agency(),
                    AfterReferenceMonthReleaseResponse.from(
                            indicator.afterReferenceMonthRelease()));
        }
    }

    public record PathPointResponse(YearMonth month, BigDecimal value) {
        static PathPointResponse from(PathPoint point) {
            return new PathPointResponse(point.month(), point.value());
        }
    }

    public record AfterReferenceMonthReleaseResponse(
            YearMonth referenceMonth, BigDecimal value, String unit) {
        static AfterReferenceMonthReleaseResponse from(AfterReferenceMonthRelease release) {
            return release == null
                    ? null
                    : new AfterReferenceMonthReleaseResponse(
                            release.referenceMonth(), release.value(), release.unit());
        }
    }
}
