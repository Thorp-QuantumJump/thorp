package com.ssafy.thorp.marketflow.application.service;

import com.ssafy.thorp.marketflow.application.port.MacroIndicatorSnapshotPort;
import com.ssafy.thorp.marketflow.application.result.MarketFlowSnapshot;
import com.ssafy.thorp.marketflow.domain.model.FieldReferenceSnapshot.FieldReferenceMonth;
import com.ssafy.thorp.marketflow.domain.model.IndicatorMetadata;
import com.ssafy.thorp.marketflow.domain.model.MarketFlowField;
import com.ssafy.thorp.marketflow.domain.vo.FieldId;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Service;

/** 현재 버전의 분야 요약과 한 분야의 실제 지표 표시 항목을 조회한다. */
@Service
public class MarketFlowQueryService {

    private final FieldSummaryBuilder summaryBuilder;
    private final MacroIndicatorSnapshotPort snapshotPort;
    private final IndicatorDisplayAssembler indicatorAssembler;

    public MarketFlowQueryService(
            FieldSummaryBuilder summaryBuilder,
            MacroIndicatorSnapshotPort snapshotPort,
            IndicatorDisplayAssembler indicatorAssembler) {
        this.summaryBuilder = summaryBuilder;
        this.snapshotPort = snapshotPort;
        this.indicatorAssembler = indicatorAssembler;
    }

    public MarketFlowSnapshot getFields() {
        return summaryBuilder.ensureCurrentSnapshot();
    }

    public MarketFlowField getField(FieldId fieldId) {
        MarketFlowSnapshot snapshot = summaryBuilder.ensureCurrentSnapshot();
        MarketFlowField summary =
                snapshot.fields().stream()
                        .filter(field -> field.fieldId() == fieldId)
                        .findFirst()
                        .orElseThrow(() -> new IllegalArgumentException("시장 흐름 분야를 찾을 수 없습니다."));
        FieldReferenceMonth reference =
                snapshotPort.loadFieldReferenceMonths().fields().stream()
                        .filter(field -> field.fieldId() == fieldId)
                        .findFirst()
                        .orElseThrow(() -> new IllegalArgumentException("시장 흐름 분야를 찾을 수 없습니다."));
        List<IndicatorMetadata> metadata =
                snapshotPort.loadIndicatorMeta(snapshot.registryVersion()).stream()
                        .filter(item -> item.fieldId() == fieldId)
                        .sorted(Comparator.comparingInt(IndicatorMetadata::displayOrder))
                        .toList();
        return new MarketFlowField(
                summary.fieldId(),
                summary.displayName(),
                summary.displayOrder(),
                summary.status(),
                summary.narrative(),
                summary.narrativeRuleVersion(),
                summary.narrativeSubjects(),
                summary.referenceMonth(),
                summary.referenceMonthHeldBackBy(),
                summary.lastUpdatedAt(),
                summary.exampleData(),
                indicatorAssembler.assemble(reference, metadata));
    }
}
