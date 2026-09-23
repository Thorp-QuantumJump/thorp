package com.ssafy.thorp.market.application.service;

import java.time.Duration;

/**
 * 최근 흐름(series) 갱신 정책.
 *
 * @param window 돌려주는 거래일 수
 * @param lookbackDays 거래일 {@code window}개를 덮는 달력 날짜 수
 * @param refreshBudget 지표 하나를 한 번 갱신할 때의 시간 예산
 * @param maxAge 마지막 성공 뒤 이 시간이 지나면 stale로 알린다
 */
public record MarketSeriesPolicy(
        int window, int lookbackDays, Duration refreshBudget, Duration maxAge) {
    public MarketSeriesPolicy {
        if (window <= 0
                || lookbackDays < window
                || refreshBudget.isNegative()
                || refreshBudget.isZero()
                || maxAge.isNegative()
                || maxAge.isZero()) {
            throw new IllegalArgumentException("시장 흐름 갱신 설정이 올바르지 않습니다.");
        }
    }
}
