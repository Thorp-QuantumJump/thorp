package com.ssafy.thorp.backtest.presentation.response;

import com.ssafy.thorp.backtest.application.result.UsedStrategy;
import java.util.List;
import java.util.Map;

/**
 * {@code GET /backtests/used-strategies} 응답. 계약은 {@code docs/specs/backtest-api/README.md} §1.12.
 *
 * <p>중복 없이 최근 순이다. 설정값은 접수가 저장한 JSON 그대로 내보낸다 — 화면이 현재 등록표로 유효성을 다시 판정한다.
 */
public record UsedStrategiesResponse(List<Item> items) {

    public static UsedStrategiesResponse from(List<UsedStrategy> used) {
        return new UsedStrategiesResponse(
                used.stream()
                        .map(u -> new Item(u.strategyId(), u.rulesVersion(), u.parameters()))
                        .toList());
    }

    public record Item(String strategyId, String rulesVersion, Map<String, Object> parameters) {}
}
