package com.ssafy.thorp.backtest.application.port.message;

import java.time.Instant;

/** STARTED 이벤트 payload. */
public record StartedPayload(Instant startedAt) {}
