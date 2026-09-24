package com.ssafy.thorp.assistant.application.result;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * 요청 전체의 시간 예산(FR-012, 15초). 라운드별 모델 호출 예산은 이 안에서 남은 시간과 작은 쪽을 쓴다 — {@code MarketHttpClient}의
 * {@code deadline.remaining()} 방식이다. 도구 실행·검증 시간도 여기에 든다.
 */
public final class Deadline {
    private final Clock clock;
    private final Instant end;

    public Deadline(Clock clock, Duration budget) {
        this.clock = clock;
        this.end = clock.instant().plus(budget);
    }

    public Duration remaining() {
        Duration left = Duration.between(clock.instant(), end);
        return left.isNegative() ? Duration.ZERO : left;
    }

    public boolean expired() {
        return remaining().isZero();
    }

    /** 라운드 예산과 남은 시간 중 작은 쪽. */
    public Duration slice(Duration roundBudget) {
        Duration left = remaining();
        return left.compareTo(roundBudget) < 0 ? left : roundBudget;
    }
}
