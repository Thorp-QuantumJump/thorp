package com.ssafy.thorp.backtest.application.command;

import com.ssafy.thorp.backtest.application.port.message.Scenario;
import com.ssafy.thorp.backtest.domain.model.ExecutionType;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 접수 유스케이스 입력(spec 004 T005). 컨트롤러가 요청 DTO를 이 모양으로 바꿔 넘긴다.
 *
 * @param requesterId 요청자. 계산 기록이 아니라 요청에만 붙는다(R-12). 취소·조회 권한의 근거다. null = 비로그인 요청(spec 005 B01) —
 *     결과는 공개 토큰으로만 열리고 토큰에 만료가 붙는다
 * @param warmupTradingDays null = 선택 전략의 관측 길이를 쓴다
 * @param scenarioParams 시나리오 입력값. 없으면 null
 */
public record CreateBacktestCommand(
        Long requesterId,
        ExecutionType executionType,
        String mappingVersion,
        List<AllocationItem> items,
        String strategyId,
        String rulesVersion,
        String registryVersion,
        Map<String, Object> parameters,
        LocalDate start,
        LocalDate end,
        BigDecimal initialCash,
        String costProfileVersion,
        String evaluationSplitVersion,
        Integer warmupTradingDays,
        Scenario scenario,
        Map<String, Object> scenarioParams) {

    /**
     * @param targetWeight FIXED_WEIGHT 에서만 존재
     */
    public record AllocationItem(String sectorId, String instrumentId, BigDecimal targetWeight) {}

    public List<String> instrumentIds() {
        return items.stream().map(AllocationItem::instrumentId).toList();
    }
}
