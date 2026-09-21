package com.ssafy.thorp.marketflow.infrastructure.macro;

import com.ssafy.thorp.macro.application.result.MacroSnapshotResult;
import com.ssafy.thorp.macro.application.service.MacroSnapshotQueryService;
import com.ssafy.thorp.marketflow.application.port.MacroIndicatorSnapshotPort;
import com.ssafy.thorp.marketflow.domain.model.AfterReferenceMonthRelease;
import com.ssafy.thorp.marketflow.domain.model.FieldReferenceSnapshot;
import com.ssafy.thorp.marketflow.domain.model.FieldReferenceSnapshot.FieldReferenceMonth;
import com.ssafy.thorp.marketflow.domain.model.IndicatorCollectionState;
import com.ssafy.thorp.marketflow.domain.model.IndicatorMetadata;
import com.ssafy.thorp.marketflow.domain.model.IndicatorPathSnapshot;
import com.ssafy.thorp.marketflow.domain.vo.FieldId;
import com.ssafy.thorp.marketflow.domain.vo.PathPoint;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 009 시장 흐름이 008 스냅샷을 읽는 어댑터 (T064, 008 FR-023).
 *
 * <p><b>한 번 읽어 넷으로 나눈다.</b> 008은 {@link MacroSnapshotQueryService#getSnapshot()} 하나로 세 버전을 고정한 뒤
 * 분야·지표·수집 상태를 함께 돌려준다. 포트의 메서드마다 따로 질의하면 그 사이에 자료 버전이 바뀔 수 있고, 그러면 한 화면 안에서 기준월과 값이 서로 다른 버전에서 온다.
 *
 * <p><b>값을 만들어 내지 않는다.</b> 008이 경로를 세 달로 주지 못하면(중단된 지표, 기준월 없음) 빈 결과를 돌려주고, 화면은 그것을 세 개의 빈 점으로
 * 그린다(원칙 VI). 여기서 달을 채우거나 이전 값을 끌어오면 화면은 자료가 있는 것처럼 보인다.
 */
@Component
@ConditionalOnProperty(
        name = "thorp.marketflow.indicator-source",
        havingValue = "macro",
        matchIfMissing = true)
public class MacroSnapshotIndicatorAdapter implements MacroIndicatorSnapshotPort {

    /** 화면이 그리는 경로의 길이. 008과 같은 t-2·t-1·t 세 달이다. */
    private static final int PATH_MONTHS = 3;

    private final MacroSnapshotQueryService queryService;

    public MacroSnapshotIndicatorAdapter(MacroSnapshotQueryService queryService) {
        this.queryService = queryService;
    }

    @Override
    public FieldReferenceSnapshot loadFieldReferenceMonths() {
        MacroSnapshotResult snapshot = queryService.getSnapshot();
        Map<String, YearMonth> latestByIndicator = latestMonths(snapshot);
        List<FieldReferenceMonth> fields =
                snapshot.fields().stream()
                        .map(field -> toFieldReference(field, latestByIndicator))
                        .toList();
        return new FieldReferenceSnapshot(
                snapshot.calculationRegistryVersion(), requireDataVersion(snapshot), fields);
    }

    @Override
    public Optional<IndicatorPathSnapshot> loadRecentPath(
            String indicatorId, YearMonth referenceMonth) {
        MacroSnapshotResult snapshot = queryService.getSnapshot();
        return snapshot.indicators().stream()
                .filter(indicator -> indicator.indicatorId().equals(indicatorId))
                .findFirst()
                .flatMap(indicator -> toPathSnapshot(indicator, referenceMonth));
    }

    @Override
    public List<IndicatorMetadata> loadIndicatorMeta(String registryVersion) {
        MacroSnapshotResult snapshot = queryService.getSnapshot();
        // 다른 등록표 버전을 물으면 빈 목록이다. 지금 버전의 메타를 옛 버전 요청에 돌려주면
        // 화면은 그 사실을 모른 채 다른 규칙으로 만든 값과 이름을 나란히 놓는다(FR-003).
        if (!snapshot.calculationRegistryVersion().equals(registryVersion)) {
            return List.of();
        }
        return snapshot.indicators().stream()
                .map(MacroSnapshotIndicatorAdapter::toMetadata)
                .toList();
    }

    @Override
    public List<IndicatorCollectionState> loadCollectionState() {
        MacroSnapshotResult snapshot = queryService.getSnapshot();
        List<String> failed = snapshot.collection().failedIndicatorIds();
        return snapshot.indicators().stream()
                .map(
                        indicator ->
                                new IndicatorCollectionState(
                                        indicator.indicatorId(),
                                        indicator.lastUpdatedAt(),
                                        // 지표 상태가 READY 여도 이번 수집에서 실패했으면 성공이
                                        // 아니다. 어제 값이 남아 있는 것과 오늘 받은 것은 다르다.
                                        "READY".equals(indicator.status())
                                                && !failed.contains(indicator.indicatorId()),
                                        snapshot.exampleData()))
                .toList();
    }

    /**
     * 지표별 최신 확보월. 분야 요약이 어느 지표가 기준월을 뒤로 당기는지 보일 때 쓴다.
     *
     * <p>값이 없는 지표는 넣지 않는다 — 없는 달을 기준월로 적으면 그 지표가 최신인 것처럼 보인다.
     */
    private Map<String, YearMonth> latestMonths(MacroSnapshotResult snapshot) {
        Map<String, YearMonth> months = new LinkedHashMap<>();
        for (MacroSnapshotResult.IndicatorSummary indicator : snapshot.indicators()) {
            if (indicator.latest() != null && indicator.latest().targetMonth() != null) {
                months.put(
                        indicator.indicatorId(), YearMonth.from(indicator.latest().targetMonth()));
            }
        }
        return months;
    }

    private FieldReferenceMonth toFieldReference(
            MacroSnapshotResult.FieldSummary field, Map<String, YearMonth> latestByIndicator) {
        List<String> indicatorIds = field.indicatorIds();
        Map<String, YearMonth> latest = new LinkedHashMap<>();
        for (String indicatorId : indicatorIds) {
            YearMonth month = latestByIndicator.get(indicatorId);
            if (month != null) {
                latest.put(indicatorId, month);
            }
        }
        return new FieldReferenceMonth(
                FieldId.from(field.fieldId()),
                field.displayName(),
                field.displayOrder(),
                field.referenceMonth() == null ? null : YearMonth.from(field.referenceMonth()),
                indicatorIds,
                latest,
                field.referenceMonthHeldBackBy());
    }

    private static IndicatorMetadata toMetadata(MacroSnapshotResult.IndicatorSummary indicator) {
        return new IndicatorMetadata(
                indicator.indicatorId(),
                FieldId.from(indicator.fieldId()),
                indicator.displayName(),
                // 화면이 보이는 단위는 변환 뒤 단위다. 원단위는 변환 전 값의 것이라 섞으면
                // 증가율에 "2020=100" 이 붙는다.
                indicator.transformedUnit(),
                indicator.transform(),
                indicator.source(),
                indicator.agency(),
                // 공표 정밀도는 008이 아직 채우지 않는다(T069). 지어내지 않고 비워 둔다.
                null,
                indicator.displayOrder());
    }

    private Optional<IndicatorPathSnapshot> toPathSnapshot(
            MacroSnapshotResult.IndicatorSummary indicator, YearMonth referenceMonth) {
        if (indicator.referenceMonth() == null
                || !YearMonth.from(indicator.referenceMonth()).equals(referenceMonth)) {
            return Optional.empty();
        }
        List<MacroSnapshotResult.PathPoint> points = indicator.path();
        // 세 달을 못 채우면 부분 경로를 내보내지 않는다. 화면은 빈 세 점을 그리고, 그것이
        // "자료가 없다"는 사실을 그대로 말한다.
        if (points.size() != PATH_MONTHS) {
            return Optional.empty();
        }
        List<PathPoint> path =
                points.stream()
                        .map(
                                point ->
                                        new PathPoint(
                                                YearMonth.from(point.targetMonth()),
                                                point.transformedValue()))
                        .toList();
        LocalDate releaseDate =
                indicator.referenceValue() == null
                        ? null
                        : indicator.referenceValue().releaseDate();
        return Optional.of(
                new IndicatorPathSnapshot(
                        indicator.indicatorId(),
                        referenceMonth,
                        path,
                        releaseDate,
                        afterReferenceRelease(indicator, referenceMonth)));
    }

    /**
     * 분야 기준월 뒤에 따로 발표된 값 (009 FR).
     *
     * <p>분야 기준월은 그 분야에서 가장 늦은 지표에 맞춰 당겨진다. 먼저 나온 지표는 그보다 새 달의 값을 이미 갖고 있는데, 그 값을 경로에 섞으면 분야 안의 지표들이
     * 서로 다른 달을 말하게 된다. 그래서 경로 밖에 따로 싣는다.
     */
    private AfterReferenceMonthRelease afterReferenceRelease(
            MacroSnapshotResult.IndicatorSummary indicator, YearMonth referenceMonth) {
        MacroSnapshotResult.ObservationValue latest = indicator.latest();
        if (latest == null || latest.targetMonth() == null || latest.transformedValue() == null) {
            return null;
        }
        YearMonth latestMonth = YearMonth.from(latest.targetMonth());
        if (!latestMonth.isAfter(referenceMonth)) {
            return null;
        }
        return new AfterReferenceMonthRelease(
                latestMonth, latest.transformedValue(), indicator.transformedUnit());
    }

    /**
     * 자료 버전. <b>없으면 멈춘다.</b>
     *
     * <p>분야 요약은 (등록표 버전, 자료 버전) 으로 저장·재사용되므로 자료 버전이 없으면 저장할 키가 없다. 첫 수집 전에는 기준월도 없어 경로를 만들 수 없으니, 빈
     * 화면을 그리는 대신 무엇을 해야 하는지 말한다.
     */
    private String requireDataVersion(MacroSnapshotResult snapshot) {
        if (snapshot.dataVersion() == null) {
            throw new IllegalStateException(
                    "발행된 거시지표 자료 버전이 없습니다. 등록표만 세워진 상태이며, "
                            + "data/scripts/ingest_macro.py 로 전 구간을 적재해야 시장 흐름을 그릴 수 있습니다.");
        }
        return snapshot.dataVersion();
    }
}
