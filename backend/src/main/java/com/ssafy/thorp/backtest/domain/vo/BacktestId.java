package com.ssafy.thorp.backtest.domain.vo;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 백테스트 외부 식별자. 형식은 {@code BT-{yyyyMMdd}-{6자리 이상 순번}}이며 DB PK(Long)와 구분된다.
 *
 * <p>메시지 {@code correlation_id}, SSE 경로, 프론트 노출값이 모두 이 값이다. 근거: ADR-0002 D2.
 */
public record BacktestId(String value) {

    private static final Pattern PATTERN = Pattern.compile("^BT-\\d{8}-\\d{6,}$");

    public BacktestId {
        Objects.requireNonNull(value, "backtestId must not be null");
        if (!PATTERN.matcher(value).matches()) {
            throw new IllegalArgumentException("invalid backtestId format: " + value);
        }
    }

    /** 날짜와 순번(PK)으로 만든다. 순번이 999,999를 넘으면 자릿수가 자연히 늘어난다. */
    public static BacktestId of(LocalDate date, long sequence) {
        return new BacktestId(
                "BT-"
                        + DateTimeFormatter.BASIC_ISO_DATE.format(date)
                        + "-"
                        + String.format("%06d", sequence));
    }

    /** 저장된 문자열을 형식 검증과 함께 복원한다. */
    public static BacktestId from(String value) {
        return new BacktestId(value);
    }
}
