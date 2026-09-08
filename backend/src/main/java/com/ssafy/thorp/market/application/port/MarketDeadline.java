package com.ssafy.thorp.market.application.port;

import com.ssafy.thorp.market.domain.exception.MarketDataException;
import java.time.Duration;

/** 여러 날짜를 조회해도 한 요청의 전체 시간 예산을 넘기지 않는다. */
public final class MarketDeadline {
    private final long endNanos;

    public MarketDeadline(Duration budget) {
        endNanos = System.nanoTime() + budget.toNanos();
    }

    public Duration remaining() {
        long nanos = endNanos - System.nanoTime();
        if (nanos <= 0) throw new MarketDataException(MarketDataException.Reason.TIMEOUT);
        return Duration.ofNanos(nanos);
    }
}
