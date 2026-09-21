package com.ssafy.thorp.regimelink.domain.vo;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 계산 하나(실행 × 사례 × 시나리오)의 외부 식별자 {@code RL-YYYYMMDD-NNNNNN} (data-model §4).
 *
 * <p>내부 PK를 주소에 노출하지 않는다(ADR-0002 D2). 형식은 004 {@code BacktestId}와 같은 규칙이고 접두사만 다르다.
 */
public record LinkId(String value) {

    private static final Pattern PATTERN = Pattern.compile("^RL-\\d{8}-\\d{6,}$");

    public LinkId {
        Objects.requireNonNull(value, "linkId must not be null");
        if (!PATTERN.matcher(value).matches()) {
            throw new IllegalArgumentException("invalid linkId format: " + value);
        }
    }

    /** 날짜와 순번(PK)으로 만든다. 순번이 999,999를 넘으면 자릿수가 자연히 늘어난다. */
    public static LinkId of(LocalDate date, long sequence) {
        return new LinkId(
                "RL-"
                        + DateTimeFormatter.BASIC_ISO_DATE.format(date)
                        + "-"
                        + String.format("%06d", sequence));
    }

    /** 저장된 문자열을 형식 검증과 함께 복원한다. */
    public static LinkId from(String value) {
        return new LinkId(value);
    }
}
