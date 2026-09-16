package com.ssafy.thorp.marketflow.presentation.response;

import com.ssafy.thorp.marketflow.application.result.MarketFlowSnapshot;
import com.ssafy.thorp.marketflow.domain.model.NarrativeFormatter;
import java.util.List;

/** 일곱 분야 요약 응답. 역사 비교가 최신 개정자료 기준임을 항상 함께 알린다. */
public record MarketFlowFieldsResponse(
        String registryVersion,
        String datasetVersion,
        String narrativeRuleVersion,
        String historicalComparisonNotice,
        List<FieldSummaryResponse> fields) {

    public static final String HISTORICAL_COMPARISON_NOTICE =
            "최신 개정자료를 이용한 역사 비교입니다. 당시 발표값을 재현한 값이 아닙니다.";

    public static MarketFlowFieldsResponse from(MarketFlowSnapshot snapshot) {
        return new MarketFlowFieldsResponse(
                snapshot.registryVersion(),
                snapshot.datasetVersion(),
                NarrativeFormatter.RULE_VERSION,
                HISTORICAL_COMPARISON_NOTICE,
                snapshot.fields().stream().map(FieldSummaryResponse::from).toList());
    }
}
