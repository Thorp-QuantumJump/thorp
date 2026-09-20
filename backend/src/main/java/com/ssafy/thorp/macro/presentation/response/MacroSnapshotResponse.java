package com.ssafy.thorp.macro.presentation.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.ssafy.thorp.macro.application.result.MacroSnapshotResult;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * {@code GET /macro-indicators/snapshot} 응답 (contracts/openapi.yaml).
 *
 * <p><b>월·날짜·시각은 문자열로 직접 만든다.</b> Jackson 시간 모듈 설정에 맡기면 그 설정이 바뀐 날 이 계약이 조용히 달라지고, 그 변화는 다른 도메인
 * 응답까지 함께 건드린다(regime 이 같은 이유로 같은 방식을 쓴다).
 *
 * <p><b>값은 숫자로 나간다.</b> 저장은 자릿수를 지키려 JSONB 문자열이지만 계약이 {@code number} 라 {@link BigDecimal} 로 내보낸다 —
 * 문자열로 바꾸면 009 가 계약대로 읽던 자리가 깨진다.
 */
public record MacroSnapshotResponse(
        String dataVersion,
        String calculationRegistryVersion,
        String displayVersion,
        boolean isExampleData,
        String historicalComparisonNotice,
        String overallReferenceMonth,
        CollectionStateResponse collection,
        List<FieldResponse> fields,
        List<IndicatorResponse> indicators) {

    /** 계약이 문구까지 고정한다. 화면이 이 문장을 그대로 띄울 수 있어야 한다(FR-012, 원칙 V). */
    public static final String HISTORICAL_COMPARISON_NOTICE =
            "최신 개정자료를 이용한 역사 비교입니다. 당시 발표값을 재현한 값이 아닙니다.";

    public static MacroSnapshotResponse from(MacroSnapshotResult result) {
        return new MacroSnapshotResponse(
                result.dataVersion(),
                result.calculationRegistryVersion(),
                result.displayVersion(),
                result.exampleData(),
                HISTORICAL_COMPARISON_NOTICE,
                month(result.overallReferenceMonth()),
                CollectionStateResponse.from(result.collection()),
                result.fields().stream().map(FieldResponse::from).toList(),
                result.indicators().stream().map(IndicatorResponse::from).toList());
    }

    public record CollectionStateResponse(
            String status,
            List<String> licenseUnverifiedIndicatorIds,
            String lastSuccessfulAt,
            String lastAttemptAt,
            List<String> failedIndicatorIds,
            boolean quotaLimited) {

        static CollectionStateResponse from(MacroSnapshotResult.CollectionState state) {
            return new CollectionStateResponse(
                    state.status(),
                    state.licenseUnverifiedIndicatorIds(),
                    instant(state.lastSuccessfulAt()),
                    instant(state.lastAttemptAt()),
                    state.failedIndicatorIds(),
                    state.quotaLimited());
        }
    }

    public record FieldResponse(
            String fieldId,
            String displayName,
            int displayOrder,
            String description,
            String referenceMonth,
            List<String> referenceMonthHeldBackBy,
            List<String> indicatorIds) {

        static FieldResponse from(MacroSnapshotResult.FieldSummary field) {
            return new FieldResponse(
                    field.fieldId(),
                    field.displayName(),
                    field.displayOrder(),
                    field.description(),
                    month(field.referenceMonth()),
                    field.referenceMonthHeldBackBy(),
                    field.indicatorIds());
        }
    }

    public record IndicatorResponse(
            String indicatorId,
            String fieldId,
            String displayName,
            int displayOrder,
            String source,
            String agency,
            String rawUnit,
            String transformedUnit,
            String transform,
            String status,
            String referenceMonth,
            ObservationValueResponse referenceValue,
            ObservationValueResponse latest,
            List<PathPointResponse> path,
            List<PathPointResponse> latestPath,
            String lastUpdatedAt) {

        static IndicatorResponse from(MacroSnapshotResult.IndicatorSummary indicator) {
            return new IndicatorResponse(
                    indicator.indicatorId(),
                    indicator.fieldId(),
                    indicator.displayName(),
                    indicator.displayOrder(),
                    indicator.source(),
                    indicator.agency(),
                    indicator.rawUnit(),
                    indicator.transformedUnit(),
                    indicator.transform(),
                    indicator.status(),
                    month(indicator.referenceMonth()),
                    ObservationValueResponse.from(indicator.referenceValue()),
                    ObservationValueResponse.from(indicator.latest()),
                    indicator.path().stream().map(PathPointResponse::from).toList(),
                    indicator.latestPath().stream().map(PathPointResponse::from).toList(),
                    instant(indicator.lastUpdatedAt()));
        }
    }

    public record ObservationValueResponse(
            String targetMonth,
            BigDecimal rawValue,
            BigDecimal transformedValue,
            String releaseDate) {

        static ObservationValueResponse from(MacroSnapshotResult.ObservationValue value) {
            return value == null
                    ? null
                    : new ObservationValueResponse(
                            month(value.targetMonth()),
                            value.rawValue(),
                            value.transformedValue(),
                            date(value.releaseDate()));
        }
    }

    /**
     * 경로 한 점.
     *
     * <p>{@code missingReason} 만 값이 있을 때 키를 생략한다(계약). 나머지 넷은 <b>null 이어도 키를 남긴다</b> — 계약이 필수로 두었고,
     * 키가 사라지면 화면은 값이 없는 달과 아예 오지 않은 달을 구분하지 못한다.
     */
    public record PathPointResponse(
            String targetMonth,
            BigDecimal rawValue,
            BigDecimal transformedValue,
            String releaseDate,
            @JsonInclude(JsonInclude.Include.NON_NULL) String missingReason) {

        static PathPointResponse from(MacroSnapshotResult.PathPoint point) {
            return new PathPointResponse(
                    month(point.targetMonth()),
                    point.rawValue(),
                    point.transformedValue(),
                    date(point.releaseDate()),
                    point.missingReason());
        }
    }

    private static String month(LocalDate value) {
        return value == null ? null : "%04d-%02d".formatted(value.getYear(), value.getMonthValue());
    }

    private static String date(LocalDate value) {
        return value == null ? null : value.toString();
    }

    private static String instant(Instant value) {
        return value == null ? null : value.toString();
    }
}
