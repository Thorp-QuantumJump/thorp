package com.ssafy.thorp.regimelink.application.port.message;

/**
 * 사후 구간을 무엇을 기준으로 재는가(research R3).
 *
 * <p>{@code CONTINUOUS}는 기존 실행을 그대로 이어 본 것이라 기준값이 {@code inputs.portfolioSeries}의 {@code
 * baselineDate} 평가액이고, {@code FRESH_CASH}는 사례 직후에 새로 시작한 것이라 기준값이 {@code run.initialCash}다. 시작 보유도
 * 앞의 것은 {@code openingPositions}가 있고 뒤의 것은 빈 목록이다.
 */
public enum RegimeLinkScenario {
    CONTINUOUS,
    FRESH_CASH
}
