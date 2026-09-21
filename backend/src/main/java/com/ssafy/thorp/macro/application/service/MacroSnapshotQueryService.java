package com.ssafy.thorp.macro.application.service;

import com.ssafy.thorp.macro.application.exception.MacroRegistryNotPublishedException;
import com.ssafy.thorp.macro.application.repository.MacroSnapshotRepository;
import com.ssafy.thorp.macro.application.repository.MacroSnapshotRepository.BaselineRow;
import com.ssafy.thorp.macro.application.repository.MacroSnapshotRepository.CollectionStateRow;
import com.ssafy.thorp.macro.application.repository.MacroSnapshotRepository.CurrentPointer;
import com.ssafy.thorp.macro.application.repository.MacroSnapshotRepository.DisplayFieldRow;
import com.ssafy.thorp.macro.application.repository.MacroSnapshotRepository.DisplayIndicatorRow;
import com.ssafy.thorp.macro.application.repository.MacroSnapshotRepository.IndicatorDefinitionRow;
import com.ssafy.thorp.macro.application.repository.MacroSnapshotRepository.MonthKey;
import com.ssafy.thorp.macro.application.repository.MacroSnapshotRepository.ObservationRow;
import com.ssafy.thorp.macro.application.result.MacroSnapshotResult;
import com.ssafy.thorp.macro.application.result.MacroSnapshotResult.CollectionState;
import com.ssafy.thorp.macro.application.result.MacroSnapshotResult.FieldSummary;
import com.ssafy.thorp.macro.application.result.MacroSnapshotResult.IndicatorSummary;
import com.ssafy.thorp.macro.application.result.MacroSnapshotResult.ObservationValue;
import com.ssafy.thorp.macro.application.result.MacroSnapshotResult.PathPoint;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 17개 지표의 표시 정보를 한 응답으로 조립한다 (spec 008 FR-023).
 *
 * <p><b>계산하지 않는다.</b> 값·기준월·결측 사유는 모두 {@code data/} 배치가 적재해 둔 것이고 여기서는 읽어 모으기만 한다(루트 {@code
 * AGENTS.md}).
 *
 * <p><b>세 버전을 먼저 고정한 뒤 조립한다.</b> 메서드 전체가 한 읽기 트랜잭션이라 조회 도중 포인터가 바뀌어도 이 응답은 처음 읽은 세 버전만 본다. 고정하지 않으면
 * 분야 기준월은 새 자료 버전에서, 값은 옛 버전에서 오는 응답이 만들어지고 그 화면이 어느 시점인지 아무도 말할 수 없다.
 */
@Service
public class MacroSnapshotQueryService {

    /** 이용이 중단된 지표의 허락 상태. 등록표·스키마와 같은 값이다(FR-016b). */
    private static final String SUSPENDED_LICENSE = "SUSPENDED";

    /** 경로는 t-2·t-1·t 세 달이다. */
    private static final int PATH_MONTHS = 3;

    private static final String SCOPE_ALL = "ALL";

    private final MacroSnapshotRepository repository;

    public MacroSnapshotQueryService(MacroSnapshotRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public MacroSnapshotResult getSnapshot() {
        CurrentPointer pointer =
                repository
                        .findCurrentPointer()
                        .orElseThrow(MacroRegistryNotPublishedException::new);

        List<IndicatorDefinitionRow> definitions =
                repository.findDefinitions(pointer.registryVersionId());
        List<DisplayFieldRow> displayFields =
                repository.findDisplayFields(pointer.displayVersionId());
        Map<String, DisplayIndicatorRow> displayIndicators =
                repository.findDisplayIndicators(pointer.displayVersionId()).stream()
                        .collect(
                                Collectors.toMap(
                                        DisplayIndicatorRow::indicatorId, Function.identity()));

        Long datasetVersionId = pointer.datasetVersionId();
        Map<String, BaselineRow> baselines =
                datasetVersionId == null
                        ? Map.of()
                        : repository
                                .findBaselines(datasetVersionId, pointer.registryVersionId())
                                .stream()
                                .collect(
                                        Collectors.toMap(
                                                BaselineRow::scopeId, Function.identity()));
        Map<String, LocalDate> latestMonths =
                datasetVersionId == null
                        ? Map.of()
                        : repository.findLatestValidMonths(datasetVersionId).stream()
                                .collect(
                                        Collectors.toMap(
                                                MacroSnapshotRepository.LatestMonthRow::indicatorId,
                                                MacroSnapshotRepository.LatestMonthRow
                                                        ::targetMonth));

        Map<String, Map<LocalDate, ObservationRow>> observations =
                loadObservations(datasetVersionId, definitions, baselines, latestMonths);

        CollectionState collection = toCollectionState(repository.findLatestCollectionState());
        Set<String> failed = Set.copyOf(collection.failedIndicatorIds());

        List<IndicatorSummary> indicators =
                definitions.stream()
                        .map(
                                definition ->
                                        toIndicator(
                                                definition,
                                                displayIndicators.get(definition.indicatorId()),
                                                baselines.get(definition.fieldId()),
                                                latestMonths.get(definition.indicatorId()),
                                                observations.getOrDefault(
                                                        definition.indicatorId(), Map.of()),
                                                pointer.sampleData(),
                                                failed))
                        .sorted(Comparator.comparingInt(IndicatorSummary::displayOrder))
                        .toList();

        return new MacroSnapshotResult(
                pointer.datasetVersionKey(),
                pointer.registryVersionKey(),
                pointer.displayVersionKey(),
                pointer.sampleData(),
                Optional.ofNullable(baselines.get(SCOPE_ALL))
                        .map(BaselineRow::referenceMonth)
                        .orElse(null),
                collection,
                toFields(displayFields, definitions, baselines),
                indicators);
    }

    /**
     * 필요한 (지표, 대상월)만 모아 한 번에 읽는다.
     *
     * <p>전 구간을 끌어와 자바에서 고르지 않는 이유는 17개 × 수백 달이면 매 요청마다 수천 행을 옮기게 되어서다. 필요한 달은 분야 T_g 세 달과 지표 최신월 세
     * 달뿐이라 미리 셀 수 있다.
     */
    private Map<String, Map<LocalDate, ObservationRow>> loadObservations(
            Long datasetVersionId,
            List<IndicatorDefinitionRow> definitions,
            Map<String, BaselineRow> baselines,
            Map<String, LocalDate> latestMonths) {
        if (datasetVersionId == null) {
            return Map.of();
        }
        Set<MonthKey> keys = new LinkedHashSet<>();
        for (IndicatorDefinitionRow definition : definitions) {
            BaselineRow baseline = baselines.get(definition.fieldId());
            if (baseline != null && baseline.referenceMonth() != null) {
                addPathKeys(keys, definition.indicatorId(), baseline.referenceMonth());
            }
            LocalDate latest = latestMonths.get(definition.indicatorId());
            if (latest != null) {
                addPathKeys(keys, definition.indicatorId(), latest);
            }
        }
        if (keys.isEmpty()) {
            return Map.of();
        }
        Map<String, Map<LocalDate, ObservationRow>> byIndicator = new LinkedHashMap<>();
        for (ObservationRow row : repository.findObservations(datasetVersionId, keys)) {
            byIndicator
                    .computeIfAbsent(row.indicatorId(), key -> new LinkedHashMap<>())
                    .put(row.targetMonth(), row);
        }
        return byIndicator;
    }

    private void addPathKeys(Set<MonthKey> keys, String indicatorId, LocalDate anchor) {
        for (int back = PATH_MONTHS - 1; back >= 0; back--) {
            keys.add(new MonthKey(indicatorId, anchor.minusMonths(back)));
        }
    }

    private List<FieldSummary> toFields(
            List<DisplayFieldRow> displayFields,
            List<IndicatorDefinitionRow> definitions,
            Map<String, BaselineRow> baselines) {
        Map<String, List<String>> membersByField = new LinkedHashMap<>();
        for (IndicatorDefinitionRow definition : definitions) {
            membersByField
                    .computeIfAbsent(definition.fieldId(), key -> new ArrayList<>())
                    .add(definition.indicatorId());
        }
        return displayFields.stream()
                .sorted(Comparator.comparingInt(DisplayFieldRow::displayOrder))
                .map(
                        field -> {
                            BaselineRow baseline = baselines.get(field.fieldId());
                            return new FieldSummary(
                                    field.fieldId(),
                                    field.displayName(),
                                    field.displayOrder(),
                                    field.description(),
                                    baseline == null ? null : baseline.referenceMonth(),
                                    baseline == null ? List.of() : baseline.heldBackBy(),
                                    membersByField.getOrDefault(field.fieldId(), List.of()));
                        })
                .toList();
    }

    private IndicatorSummary toIndicator(
            IndicatorDefinitionRow definition,
            DisplayIndicatorRow display,
            BaselineRow fieldBaseline,
            LocalDate latestMonth,
            Map<LocalDate, ObservationRow> observations,
            boolean sampleData,
            Set<String> failedIndicatorIds) {
        LocalDate fieldReferenceMonth =
                fieldBaseline == null ? null : fieldBaseline.referenceMonth();
        ObservationRow referenceRow =
                fieldReferenceMonth == null ? null : observations.get(fieldReferenceMonth);
        ObservationRow latestRow = latestMonth == null ? null : observations.get(latestMonth);

        // 중단된 지표는 값을 내보내지 않는다(FR-016b). 등록 정보와 중단 사유는 남겨
        // 조용히 사라지지 않고 "빠진 상태"임이 드러나게 한다(원칙 VI).
        boolean suspended = SUSPENDED_LICENSE.equals(definition.licenseStatus());

        return new IndicatorSummary(
                definition.indicatorId(),
                definition.fieldId(),
                display == null ? definition.indicatorId() : display.displayName(),
                display == null ? Integer.MAX_VALUE : display.displayOrder(),
                definition.attribution(),
                definition.agency(),
                definition.rawUnit(),
                definition.transformedUnit(),
                definition.transformRule(),
                resolveStatus(definition, latestMonth, sampleData, failedIndicatorIds),
                suspended ? null : fieldReferenceMonth,
                suspended ? null : toValue(referenceRow),
                suspended ? null : toValue(latestRow),
                suspended ? List.of() : toPath(fieldReferenceMonth, observations),
                suspended ? List.of() : toPath(latestMonth, observations),
                latestRow == null ? null : latestRow.receivedAt(),
                suspended ? definition.licenseSuspendedReason() : null);
    }

    /**
     * 지표 상태. <b>예시 자료가 가장 먼저다</b> — 개발 대역으로 만든 값이 READY 로 보이면 운영과 구분되지 않는다(FR-022).
     *
     * <p>수집 실패는 값이 있어도 표시한다. 어제 자료가 남아 있어도 오늘 못 받았다는 사실은 숨기지 않는다(FR-011).
     */
    private String resolveStatus(
            IndicatorDefinitionRow definition,
            LocalDate latestMonth,
            boolean sampleData,
            Set<String> failedIndicatorIds) {
        // **중단이 가장 먼저다.** 이용 권리가 거둬들여진 지표는 개발 대역이든 운영이든 값을
        // 내보내지 않는다(FR-016b). 예시 자료 뒤에 두면 개발 프로필에서 중단이 가려진다.
        if (SUSPENDED_LICENSE.equals(definition.licenseStatus())) {
            return "SUSPENDED";
        }
        if (sampleData) {
            return "EXAMPLE";
        }
        if (failedIndicatorIds.contains(definition.indicatorId())) {
            return "COLLECTION_FAILED";
        }
        return latestMonth == null ? "NO_DATA" : "READY";
    }

    private ObservationValue toValue(ObservationRow row) {
        if (row == null || row.transformedValue() == null) {
            return null;
        }
        return new ObservationValue(
                row.targetMonth(), row.rawValue(), row.transformedValue(), row.releaseDate());
    }

    /**
     * 기준월에서 t-2·t-1·t 세 달. 기준월이 없으면 빈 배열이다.
     *
     * <p><b>빠진 달을 배열에서 지우지 않는다.</b> 값이 없는 달도 자리를 지키고 사유를 달아 보낸다 — 지우면 세 달이 두 달로 보이고 화면은 어느 달이 비었는지
     * 알 수 없다(원칙 VI).
     */
    private List<PathPoint> toPath(LocalDate anchor, Map<LocalDate, ObservationRow> observations) {
        if (anchor == null) {
            return List.of();
        }
        List<PathPoint> points = new ArrayList<>(PATH_MONTHS);
        for (int back = PATH_MONTHS - 1; back >= 0; back--) {
            LocalDate month = anchor.minusMonths(back);
            ObservationRow row = observations.get(month);
            if (row == null) {
                points.add(new PathPoint(month, null, null, null, "NOT_RELEASED"));
            } else {
                points.add(
                        new PathPoint(
                                month,
                                row.rawValue(),
                                row.transformedValue(),
                                row.releaseDate(),
                                row.transformedValue() == null
                                        ? toPublicReason(row.missingReason())
                                        : null));
            }
        }
        return points;
    }

    /**
     * 저장된 결측 사유를 계약이 정한 네 가지로 좁힌다.
     *
     * <p>저장 쪽은 왜 못 만들었는지를 열 가지 넘게 구분한다 — 분모가 결측인 것과 0인 것, 계열 시작 이전, 월 평균 표본 미완성처럼 고치는 방법이 저마다 다르기
     * 때문이다. 화면이 알아야 하는 것은 그보다 거칠다. <b>세분을 그대로 내보내면 계약에 없는 값이 화면에 닿는다.</b>
     */
    private String toPublicReason(String storedReason) {
        if (storedReason == null) {
            return "NOT_TRANSFORMABLE";
        }
        return switch (storedReason) {
            case "SOURCE_NOT_RELEASED", "SOURCE_MISSING" -> "NOT_RELEASED";
            case "COLLECTION_FAILED" -> "COLLECTION_FAILED";
            case "BREAK_SEGMENT_EXCLUDED" -> "BREAK_EXCLUDED";
            default -> "NOT_TRANSFORMABLE";
        };
    }

    private CollectionState toCollectionState(Optional<CollectionStateRow> row) {
        return row.map(
                        state ->
                                new CollectionState(
                                        state.status(),
                                        List.of(),
                                        state.lastSuccessfulAt(),
                                        state.lastAttemptAt(),
                                        state.failedIndicatorIds(),
                                        state.quotaLimited()))
                .orElseGet(
                        () ->
                                new CollectionState(
                                        "NEVER_COLLECTED",
                                        List.of(),
                                        null,
                                        null,
                                        List.of(),
                                        false));
    }
}
