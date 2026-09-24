package com.ssafy.thorp.assistant;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/** 테스트가 시간을 앞으로 돌리는 시계. 가짜 모델이 "오래 걸린 척" 하거나 한도 창을 넘길 때 쓴다. */
public final class MutableClock extends Clock {
    private Instant now;

    public MutableClock(Instant start) {
        this.now = start;
    }

    public void advance(Duration by) {
        now = now.plus(by);
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return this;
    }

    @Override
    public Instant instant() {
        return now;
    }
}
