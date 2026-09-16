package com.ssafy.thorp.marketflow.application.result;

import com.ssafy.thorp.marketflow.domain.model.MarketFlowField;
import java.util.List;

/** 한 등록표·자료 버전에서 고정된 일곱 분야 읽기 결과. */
public record MarketFlowSnapshot(
        String registryVersion, String datasetVersion, List<MarketFlowField> fields) {
    public MarketFlowSnapshot {
        fields = List.copyOf(fields);
    }
}
