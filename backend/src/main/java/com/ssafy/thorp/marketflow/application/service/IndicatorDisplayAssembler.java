package com.ssafy.thorp.marketflow.application.service;

import com.ssafy.thorp.marketflow.application.port.MacroIndicatorSnapshotPort;
import com.ssafy.thorp.marketflow.domain.model.FieldReferenceSnapshot.FieldReferenceMonth;
import com.ssafy.thorp.marketflow.domain.model.IndicatorDisplay;
import com.ssafy.thorp.marketflow.domain.model.IndicatorMetadata;
import com.ssafy.thorp.marketflow.domain.model.IndicatorPathSnapshot;
import com.ssafy.thorp.marketflow.domain.vo.PathPoint;
import java.time.YearMonth;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Component;

/** 포트의 경로와 등록표 메타를 화면 표시 항목으로 합친다. 결측 경로는 null 세 점으로 유지한다. */
@Component
public class IndicatorDisplayAssembler {

    private final MacroIndicatorSnapshotPort snapshotPort;

    public IndicatorDisplayAssembler(MacroIndicatorSnapshotPort snapshotPort) {
        this.snapshotPort = snapshotPort;
    }

    public List<IndicatorDisplay> assemble(
            FieldReferenceMonth reference, List<IndicatorMetadata> metadata) {
        return metadata.stream()
                .filter(item -> item.fieldId() == reference.fieldId())
                .sorted(Comparator.comparingInt(IndicatorMetadata::displayOrder))
                .map(item -> assembleOne(reference, item))
                .toList();
    }

    private IndicatorDisplay assembleOne(
            FieldReferenceMonth reference, IndicatorMetadata metadata) {
        IndicatorPathSnapshot snapshot =
                snapshotPort
                        .loadRecentPath(metadata.indicatorId(), reference.referenceMonth())
                        .orElseGet(
                                () ->
                                        emptyPath(
                                                metadata.indicatorId(),
                                                reference.referenceMonth()));
        return new IndicatorDisplay(
                metadata.indicatorId(),
                metadata.name(),
                metadata.unit(),
                snapshot.path().getLast().value(),
                snapshot.path(),
                reference.referenceMonth(),
                snapshot.releaseDate(),
                metadata.source(),
                metadata.agency(),
                snapshot.afterReferenceMonthRelease());
    }

    private static IndicatorPathSnapshot emptyPath(String indicatorId, YearMonth referenceMonth) {
        return new IndicatorPathSnapshot(
                indicatorId,
                referenceMonth,
                List.of(
                        new PathPoint(referenceMonth.minusMonths(2), null),
                        new PathPoint(referenceMonth.minusMonths(1), null),
                        new PathPoint(referenceMonth, null)),
                null,
                null);
    }
}
