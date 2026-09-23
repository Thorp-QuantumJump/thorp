package com.ssafy.thorp.myrecord.presentation.response;

import com.ssafy.thorp.myrecord.application.result.UsedStrategy;
import java.util.List;
import java.util.Map;

/**
 * {@code GET /my-records/used-strategies} 응답. 계약은 {@code
 * specs/006-my-records/contracts/my-records-api.md}.
 *
 * <p>중복 없이 최근 순이다. 설정값은 접수가 저장한 JSON 그대로 내보낸다 — 화면이 현재 등록표로 유효성을 다시 판정한다.
 */
public record UsedStrategiesResponse(List<Item> items) {

    public static UsedStrategiesResponse from(List<UsedStrategy> used) {
        return new UsedStrategiesResponse(
                used.stream()
                        .map(
                                u ->
                                        new Item(
                                                u.strategyId(),
                                                u.recordName(),
                                                u.rulesVersion(),
                                                u.parameters()))
                        .toList());
    }

    /**
     * @param recordName 그 조합을 가리키는 내 기록 항목 이름. 지웠거나 없으면 {@code null}
     */
    public record Item(
            String strategyId,
            String recordName,
            String rulesVersion,
            Map<String, Object> parameters) {}
}
