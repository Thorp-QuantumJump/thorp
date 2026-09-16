package com.ssafy.thorp.marketflow.application.port;

import com.ssafy.thorp.marketflow.domain.model.FieldReferenceSnapshot;
import com.ssafy.thorp.marketflow.domain.model.IndicatorCollectionState;
import com.ssafy.thorp.marketflow.domain.model.IndicatorMetadata;
import com.ssafy.thorp.marketflow.domain.model.IndicatorPathSnapshot;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;

/** 009가 008의 지표 관측값·등록표·기준월·수집 상태를 읽는 유일한 경계. */
public interface MacroIndicatorSnapshotPort {

    FieldReferenceSnapshot loadFieldReferenceMonths();

    Optional<IndicatorPathSnapshot> loadRecentPath(String indicatorId, YearMonth referenceMonth);

    List<IndicatorMetadata> loadIndicatorMeta(String registryVersion);

    List<IndicatorCollectionState> loadCollectionState();
}
