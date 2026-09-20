package com.ssafy.thorp.macro.application.result;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * 17개 지표·7개 분야를 한 자료 버전에서 읽은 결과 (spec 008 FR-023).
 *
 * <p><b>세 버전은 조립 전에 고정된다.</b> 자료 버전이 없을 수 있고({@code dataVersion == null}) 그때도 등록표와 표시 버전은 있다 — 첫 수집
 * 전에도 화면은 17개가 무엇인지 보여줄 수 있어야 한다.
 */
public record MacroSnapshotResult(
        String dataVersion,
        String calculationRegistryVersion,
        String displayVersion,
        boolean exampleData,
        LocalDate overallReferenceMonth,
        CollectionState collection,
        List<FieldSummary> fields,
        List<IndicatorSummary> indicators) {

    /**
     * 수집 시도 상태. <b>공개 자료 버전의 상태와 별개다.</b>
     *
     * <p>어제 발행한 자료가 멀쩡히 있는데 오늘 새벽 수집이 실패할 수 있다. 둘을 한 값으로 합치면 화면이 "자료가 없다"와 "자료는 있는데 갱신이 멈췄다"를 구분하지
     * 못한다(FR-011).
     */
    public record CollectionState(
            String status,
            List<String> licenseUnverifiedIndicatorIds,
            Instant lastSuccessfulAt,
            Instant lastAttemptAt,
            List<String> failedIndicatorIds,
            boolean quotaLimited) {}

    /** 분야 하나. {@code referenceMonth} 는 그 분야의 T_g 이며 교집합이 비면 null 이다. */
    public record FieldSummary(
            String fieldId,
            String displayName,
            int displayOrder,
            String description,
            LocalDate referenceMonth,
            List<String> referenceMonthHeldBackBy,
            List<String> indicatorIds) {}

    /** 지표 하나. */
    public record IndicatorSummary(
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
            LocalDate referenceMonth,
            ObservationValue referenceValue,
            ObservationValue latest,
            List<PathPoint> path,
            List<PathPoint> latestPath,
            Instant lastUpdatedAt) {}

    /** 값이 있는 관측. {@code transformedValue} 는 null 이 아니다 — null 이면 {@link PathPoint} 쪽이다. */
    public record ObservationValue(
            LocalDate targetMonth,
            BigDecimal rawValue,
            BigDecimal transformedValue,
            LocalDate releaseDate) {}

    /**
     * 경로 한 점. 값이 없으면 <b>왜 없는지가 함께 온다</b>.
     *
     * <p>빠진 달을 배열에서 지우지 않고 값 null 로 남기는 이유는, 지우면 세 달이 두 달로 보이고 어느 달이 빠졌는지 알 수 없기 때문이다(원칙 VI).
     */
    public record PathPoint(
            LocalDate targetMonth,
            BigDecimal rawValue,
            BigDecimal transformedValue,
            LocalDate releaseDate,
            String missingReason) {}
}
