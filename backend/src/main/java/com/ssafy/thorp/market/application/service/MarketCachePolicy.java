package com.ssafy.thorp.market.application.service;

import java.time.Duration;

public record MarketCachePolicy(
        Duration ttl, Duration maxAge, Duration failureCooldown, Duration requestBudget) {
    public MarketCachePolicy {
        if (ttl.isNegative()
                || ttl.isZero()
                || maxAge.compareTo(ttl) < 0
                || failureCooldown.isNegative()
                || requestBudget.isNegative()
                || requestBudget.isZero()) {
            throw new IllegalArgumentException("시장 데이터 캐시·시간 제한 설정이 올바르지 않습니다.");
        }
    }
}
