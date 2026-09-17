package com.ssafy.thorp.sector.application.command;

import java.util.LinkedHashSet;
import java.util.List;

/**
 * 공통 평가 가능 기간 조회의 입력 (contracts/sector-api.md).
 *
 * <p><b>{@code strategyId}와 {@code maxWarmupTradingDays}는 둘 다 선택이지만 적어도 하나는 있어야 한다.</b> 둘 다 없으면
 * 워밍업을 적용할 수도, 화면이 스스로 계산할 재료를 내릴 수도 없어 응답이 아무 판정에도 쓰이지 않는다.
 *
 * @param sectorCodes 후보 섹터 코드. 중복은 앞선 것만 남기고 순서를 보존한다 — 산식의 동점 규칙이 입력 순서를 쓴다
 * @param strategyId 워밍업을 정할 전략. 없으면 워밍업 0으로 판정하고 화면이 {@code tradingDaysFromBase}로 다시 계산한다. 카드 여러 장을
 *     요청 한 번으로 판정하려는 화면이 이 자리를 비운다
 * @param maxWarmupTradingDays 화면이 훑을 워밍업의 상한. 있으면 그만큼의 거래일 목록을 응답에 싣는다. <b>001이 003의 파라미터 조합을 몰라도
 *     되게 하려고 화면이 자기가 필요한 만큼을 말한다</b> — 없을 때 등록표의 최대값으로 채우지 않는다
 */
public record EvaluationWindowQuery(
        List<String> sectorCodes, String strategyId, Integer maxWarmupTradingDays) {

    /** 한 요청이 내릴 수 있는 거래일 수의 상한. 25년치 거래일쯤이며 이보다 큰 요청은 거절한다. */
    public static final int MAX_WARMUP_LIMIT = 6000;

    public EvaluationWindowQuery {
        if (sectorCodes == null || sectorCodes.isEmpty()) {
            throw new IllegalArgumentException("sectors must not be empty");
        }
        sectorCodes = List.copyOf(new LinkedHashSet<>(sectorCodes));
        strategyId = blankToNull(strategyId);
        if (strategyId == null && maxWarmupTradingDays == null) {
            throw new IllegalArgumentException(
                    "strategy or maxWarmupTradingDays must be given: 둘 다 없으면 판정할 수 있는 것이 없다");
        }
        if (maxWarmupTradingDays != null
                && (maxWarmupTradingDays < 0 || maxWarmupTradingDays > MAX_WARMUP_LIMIT)) {
            throw new IllegalArgumentException(
                    "maxWarmupTradingDays must be between 0 and "
                            + MAX_WARMUP_LIMIT
                            + ": "
                            + maxWarmupTradingDays);
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
