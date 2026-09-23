package com.ssafy.thorp.marketflow.application.service;

import com.ssafy.thorp.marketflow.application.port.MacroIndicatorSnapshotPort;
import com.ssafy.thorp.marketflow.application.repository.MarketFlowFieldSummaryRepository;
import com.ssafy.thorp.marketflow.application.result.MarketFlowSnapshot;
import com.ssafy.thorp.marketflow.domain.model.FieldReferenceSnapshot;
import com.ssafy.thorp.marketflow.domain.model.FieldReferenceSnapshot.FieldReferenceMonth;
import com.ssafy.thorp.marketflow.domain.model.IndicatorCollectionState;
import com.ssafy.thorp.marketflow.domain.model.IndicatorDisplay;
import com.ssafy.thorp.marketflow.domain.model.IndicatorMetadata;
import com.ssafy.thorp.marketflow.domain.model.MarketFlowField;
import com.ssafy.thorp.marketflow.domain.model.NarrativeFormatter;
import com.ssafy.thorp.marketflow.domain.model.NarrativeSubjectSelector;
import com.ssafy.thorp.marketflow.domain.model.NarrativeSubjectSelector.SelectedSubject;
import com.ssafy.thorp.marketflow.domain.vo.FieldStatus;
import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/** 현재 등록표·자료 버전의 분야 요약을 한 번 만들고 불변 읽기 모델로 보관한다. */
@Service
public class FieldSummaryBuilder {

    private static final int FIELD_COUNT = 7;

    private final MacroIndicatorSnapshotPort snapshotPort;
    private final MarketFlowFieldSummaryRepository repository;
    private final IndicatorDisplayAssembler indicatorAssembler;
    private final NarrativeSubjectSelector subjectSelector;
    private final NarrativeFormatter formatter;
    private final FieldStatusDecider statusDecider;
    private final Clock clock;

    public FieldSummaryBuilder(
            MacroIndicatorSnapshotPort snapshotPort,
            MarketFlowFieldSummaryRepository repository,
            IndicatorDisplayAssembler indicatorAssembler,
            NarrativeSubjectSelector subjectSelector,
            NarrativeFormatter formatter,
            FieldStatusDecider statusDecider,
            Clock clock) {
        this.snapshotPort = snapshotPort;
        this.repository = repository;
        this.indicatorAssembler = indicatorAssembler;
        this.subjectSelector = subjectSelector;
        this.formatter = formatter;
        this.statusDecider = statusDecider;
        this.clock = clock;
    }

    public synchronized MarketFlowSnapshot ensureCurrentSnapshot() {
        FieldReferenceSnapshot references = snapshotPort.loadFieldReferenceMonths();
        List<MarketFlowField> stored =
                repository.findAll(references.registryVersion(), references.datasetVersion());
        if (stored.size() == FIELD_COUNT) {
            return new MarketFlowSnapshot(
                    references.registryVersion(), references.datasetVersion(), stored);
        }

        List<IndicatorMetadata> metadata =
                snapshotPort.loadIndicatorMeta(references.registryVersion());
        List<IndicatorCollectionState> collectionStates = snapshotPort.loadCollectionState();
        List<MarketFlowField> built =
                references.fields().stream()
                        .sorted(Comparator.comparingInt(FieldReferenceMonth::displayOrder))
                        .map(reference -> buildField(reference, metadata, collectionStates))
                        .toList();
        repository.saveIfAbsent(
                references.registryVersion(),
                references.datasetVersion(),
                built,
                Instant.now(clock));
        return new MarketFlowSnapshot(
                references.registryVersion(),
                references.datasetVersion(),
                repository.findAll(references.registryVersion(), references.datasetVersion()));
    }

    private MarketFlowField buildField(
            FieldReferenceMonth reference,
            List<IndicatorMetadata> allMetadata,
            List<IndicatorCollectionState> allCollectionStates) {
        List<IndicatorMetadata> metadata =
                allMetadata.stream()
                        .filter(item -> item.fieldId() == reference.fieldId())
                        .sorted(Comparator.comparingInt(IndicatorMetadata::displayOrder))
                        .toList();
        Map<String, IndicatorCollectionState> statesById =
                allCollectionStates.stream()
                        .collect(
                                Collectors.toMap(
                                        IndicatorCollectionState::indicatorId,
                                        Function.identity(),
                                        (first, ignored) -> first));
        List<IndicatorCollectionState> states =
                metadata.stream()
                        .map(item -> statesById.get(item.indicatorId()))
                        .filter(java.util.Objects::nonNull)
                        .toList();
        FieldStatus status = statusDecider.decide(metadata, states);
        List<IndicatorDisplay> indicators = indicatorAssembler.assemble(reference, metadata);
        List<SelectedSubject> subjects = subjectSelector.select(indicators);
        boolean hasCompletePath =
                indicators.stream()
                        .anyMatch(
                                indicator ->
                                        indicator.path().stream()
                                                .allMatch(point -> point.value() != null));
        String narrative =
                status == FieldStatus.EMPTY || status == FieldStatus.COLLECTION_FAILED
                        ? null
                        : formatter.format(subjects, hasCompletePath);
        Instant lastUpdatedAt =
                states.stream()
                        .map(IndicatorCollectionState::lastUpdatedAt)
                        .filter(java.util.Objects::nonNull)
                        .max(Comparator.naturalOrder())
                        .orElse(null);
        return new MarketFlowField(
                reference.fieldId(),
                reference.displayName(),
                reference.displayOrder(),
                status,
                narrative,
                NarrativeFormatter.RULE_VERSION,
                subjects.stream().map(subject -> subject.indicator().indicatorId()).toList(),
                reference.referenceMonth(),
                reference.heldBackBy(),
                lastUpdatedAt,
                status == FieldStatus.EXAMPLE,
                indicators);
    }
}
