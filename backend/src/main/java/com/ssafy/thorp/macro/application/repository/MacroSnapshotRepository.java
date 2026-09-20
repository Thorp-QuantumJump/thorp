package com.ssafy.thorp.macro.application.repository;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * {@code macro_*} 를 읽는 유일한 통로. <b>쓰기 메서드를 두지 않는다.</b>
 *
 * <p>이 표들을 채우는 것은 {@code data/src/data_pipeline/macro/} 배치다. Backend 에 쓰기 자리를 하나라도 열면 배치가 만든 불변
 * 스냅샷을 조회 경로가 고칠 수 있게 되고, 그 순간 "같은 버전이면 같은 값"이 깨진다(FR-011a).
 *
 * <p>메서드가 여럿인 이유는 조립이 여러 표에 걸쳐 있어서다. <b>호출자는 이것들을 한 트랜잭션 안에서 부른다</b> — 중간에 포인터가 바뀌면 한 응답에 서로 다른 자료
 * 버전의 값이 섞인다(FR-023).
 */
public interface MacroSnapshotRepository {

    /** 현재 공개 중인 세 버전. 아직 아무것도 발행하지 않았으면 비어 있다. */
    Optional<CurrentPointer> findCurrentPointer();

    List<IndicatorDefinitionRow> findDefinitions(long registryVersionId);

    List<DisplayFieldRow> findDisplayFields(long displayVersionId);

    List<DisplayIndicatorRow> findDisplayIndicators(long displayVersionId);

    /** 분야별 T_g 와 전체 T_all. {@code scopeId} 는 7개 분야 id 또는 {@code ALL} 이다. */
    List<BaselineRow> findBaselines(long datasetVersionId, long registryVersionId);

    /** 지표별 최신 <b>유효 변환값</b> 대상월. 원값만 있는 달은 세지 않는다. */
    List<LatestMonthRow> findLatestValidMonths(long datasetVersionId);

    /** 필요한 (지표, 대상월)만 읽는다. 전 구간을 끌어오지 않는다. */
    List<ObservationRow> findObservations(long datasetVersionId, Collection<MonthKey> keys);

    /** 가장 최근 수집 시도의 상태. 시도가 한 번도 없었으면 비어 있다. */
    Optional<CollectionStateRow> findLatestCollectionState();

    record CurrentPointer(
            Long datasetVersionId,
            String datasetVersionKey,
            long registryVersionId,
            String registryVersionKey,
            long displayVersionId,
            String displayVersionKey,
            boolean sampleData) {}

    record IndicatorDefinitionRow(
            String indicatorId,
            String fieldId,
            String attribution,
            String agency,
            String rawUnit,
            String transformedUnit,
            String transformRule,
            String licenseReviewStatus) {}

    record DisplayFieldRow(
            String fieldId, String displayName, int displayOrder, String description) {}

    record DisplayIndicatorRow(String indicatorId, String displayName, int displayOrder) {}

    record BaselineRow(String scopeId, LocalDate referenceMonth, List<String> heldBackBy) {}

    record LatestMonthRow(String indicatorId, LocalDate targetMonth) {}

    /** 조회할 (지표, 대상월) 한 쌍. */
    record MonthKey(String indicatorId, LocalDate targetMonth) {}

    record ObservationRow(
            String indicatorId,
            LocalDate targetMonth,
            BigDecimal rawValue,
            BigDecimal transformedValue,
            LocalDate releaseDate,
            Instant receivedAt,
            String missingReason) {}

    record CollectionStateRow(
            String status,
            Instant lastSuccessfulAt,
            Instant lastAttemptAt,
            List<String> failedIndicatorIds,
            boolean quotaLimited) {}
}
