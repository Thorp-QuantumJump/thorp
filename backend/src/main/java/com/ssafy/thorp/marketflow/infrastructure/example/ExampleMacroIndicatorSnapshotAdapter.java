package com.ssafy.thorp.marketflow.infrastructure.example;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ssafy.thorp.marketflow.application.port.MacroIndicatorSnapshotPort;
import com.ssafy.thorp.marketflow.domain.model.AfterReferenceMonthRelease;
import com.ssafy.thorp.marketflow.domain.model.FieldReferenceSnapshot;
import com.ssafy.thorp.marketflow.domain.model.FieldReferenceSnapshot.FieldReferenceMonth;
import com.ssafy.thorp.marketflow.domain.model.IndicatorCollectionState;
import com.ssafy.thorp.marketflow.domain.model.IndicatorMetadata;
import com.ssafy.thorp.marketflow.domain.model.IndicatorPathSnapshot;
import com.ssafy.thorp.marketflow.domain.vo.FieldId;
import com.ssafy.thorp.marketflow.domain.vo.PathPoint;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

/**
 * 008 실제 저장소가 합류하기 전 사용하는 버전 고정 예시 자료 어댑터.
 *
 * <p><b>명시적으로 고를 때만 올라온다</b>(T064). 008 스냅샷을 읽는 {@code
 * com.ssafy.thorp.marketflow.infrastructure.macro.MacroSnapshotIndicatorAdapter} 가 기본이고, 이 대역은
 * {@code thorp.marketflow.indicator-source=example} 일 때만 대신 들어온다. 둘 다 올라오면 포트 구현이 둘이라 주입이 모호해진다.
 *
 * <p>운영에서 이것이 골라지면 {@link MarketFlowExampleDataGuard} 가 기동을 막는다.
 */
@Component
@ConditionalOnProperty(name = "thorp.marketflow.indicator-source", havingValue = "example")
public class ExampleMacroIndicatorSnapshotAdapter implements MacroIndicatorSnapshotPort {

    static final String RESOURCE_PATH = "marketflow/macro-indicators-example-v1.json";

    private final FieldReferenceSnapshot references;
    private final List<IndicatorMetadata> metadata;
    private final Map<String, IndicatorPathSnapshot> paths;
    private final List<IndicatorCollectionState> collectionStates;

    @Autowired
    public ExampleMacroIndicatorSnapshotAdapter(ObjectMapper objectMapper) {
        this(new ClassPathResource(RESOURCE_PATH), objectMapper);
    }

    ExampleMacroIndicatorSnapshotAdapter(Resource resource, ObjectMapper objectMapper) {
        Document document = read(resource, objectMapper);
        List<FieldReferenceMonth> fieldReferences = new ArrayList<>();
        List<IndicatorMetadata> allMetadata = new ArrayList<>();
        Map<String, IndicatorPathSnapshot> allPaths = new LinkedHashMap<>();
        List<IndicatorCollectionState> allStates = new ArrayList<>();

        for (FieldDocument field : document.fields()) {
            FieldId fieldId = FieldId.from(field.fieldId());
            Map<String, YearMonth> latestMonths = new LinkedHashMap<>();
            List<String> indicatorIds = new ArrayList<>();
            for (IndicatorDocument indicator : field.indicators()) {
                indicatorIds.add(indicator.indicatorId());
                latestMonths.put(indicator.indicatorId(), YearMonth.parse(indicator.latestMonth()));
                allMetadata.add(toMetadata(fieldId, indicator));
                allPaths.put(indicator.indicatorId(), toPath(indicator));
                allStates.add(
                        new IndicatorCollectionState(
                                indicator.indicatorId(),
                                Instant.parse(indicator.lastUpdatedAt()),
                                true,
                                true));
            }
            fieldReferences.add(
                    new FieldReferenceMonth(
                            fieldId,
                            field.displayName(),
                            field.displayOrder(),
                            YearMonth.parse(field.referenceMonth()),
                            indicatorIds,
                            latestMonths,
                            field.heldBackBy()));
        }
        this.references =
                new FieldReferenceSnapshot(
                        document.registryVersion(), document.datasetVersion(), fieldReferences);
        this.metadata = List.copyOf(allMetadata);
        this.paths = Map.copyOf(allPaths);
        this.collectionStates = List.copyOf(allStates);
    }

    @Override
    public FieldReferenceSnapshot loadFieldReferenceMonths() {
        return references;
    }

    @Override
    public Optional<IndicatorPathSnapshot> loadRecentPath(
            String indicatorId, YearMonth referenceMonth) {
        IndicatorPathSnapshot path = paths.get(indicatorId);
        if (path == null || !path.referenceMonth().equals(referenceMonth)) {
            return Optional.empty();
        }
        return Optional.of(path);
    }

    @Override
    public List<IndicatorMetadata> loadIndicatorMeta(String registryVersion) {
        return references.registryVersion().equals(registryVersion) ? metadata : List.of();
    }

    @Override
    public List<IndicatorCollectionState> loadCollectionState() {
        return collectionStates;
    }

    private static IndicatorMetadata toMetadata(FieldId fieldId, IndicatorDocument indicator) {
        return new IndicatorMetadata(
                indicator.indicatorId(),
                fieldId,
                indicator.name(),
                indicator.unit(),
                indicator.transformation(),
                indicator.source(),
                indicator.agency(),
                indicator.publicationPrecision(),
                indicator.displayOrder());
    }

    private static IndicatorPathSnapshot toPath(IndicatorDocument indicator) {
        List<PathPoint> points =
                indicator.path().stream()
                        .map(point -> new PathPoint(YearMonth.parse(point.month()), point.value()))
                        .toList();
        AfterReferenceMonthRelease after = null;
        if (indicator.afterReferenceMonthRelease() != null) {
            ReleaseDocument release = indicator.afterReferenceMonthRelease();
            after =
                    new AfterReferenceMonthRelease(
                            YearMonth.parse(release.referenceMonth()),
                            release.value(),
                            release.unit());
        }
        return new IndicatorPathSnapshot(
                indicator.indicatorId(),
                points.getLast().month(),
                points,
                LocalDate.parse(indicator.releaseDate()),
                after);
    }

    private static Document read(Resource resource, ObjectMapper objectMapper) {
        try (InputStream input = resource.getInputStream()) {
            return objectMapper.readValue(input, Document.class);
        } catch (IOException e) {
            throw new IllegalStateException("시장 흐름 예시 자료를 읽을 수 없습니다: " + resource, e);
        }
    }

    private record Document(
            String registryVersion, String datasetVersion, List<FieldDocument> fields) {}

    private record FieldDocument(
            String fieldId,
            String displayName,
            int displayOrder,
            String referenceMonth,
            List<String> heldBackBy,
            List<IndicatorDocument> indicators) {}

    private record IndicatorDocument(
            String indicatorId,
            String name,
            String unit,
            String transformation,
            String source,
            String agency,
            String publicationPrecision,
            int displayOrder,
            String latestMonth,
            String releaseDate,
            List<PointDocument> path,
            ReleaseDocument afterReferenceMonthRelease,
            String lastUpdatedAt) {}

    private record PointDocument(String month, BigDecimal value) {}

    private record ReleaseDocument(String referenceMonth, BigDecimal value, String unit) {}
}
