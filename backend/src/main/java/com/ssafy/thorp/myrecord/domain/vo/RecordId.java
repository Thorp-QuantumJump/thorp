package com.ssafy.thorp.myrecord.domain.vo;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 기록 항목의 외부 식별자. 형식은 {@code MR-{yyyyMMdd}-{6자리 이상 순번}}이며 DB PK(Long)와 구분된다.
 *
 * <p>{@code BacktestId}·{@code ParamSearchId}와 같은 규약이다(ADR-0002 D2). 이름은 찾기 위한 표시일 뿐이고 식별은 이 값이 한다
 * — 그래서 이름에는 유일성을 강제하지 않는다(research R12).
 */
public record RecordId(String value) {

    private static final Pattern PATTERN = Pattern.compile("^MR-\\d{8}-\\d{6,}$");

    public RecordId {
        Objects.requireNonNull(value, "recordId must not be null");
        if (!PATTERN.matcher(value).matches()) {
            throw new IllegalArgumentException("invalid recordId format: " + value);
        }
    }

    /** 날짜와 순번(PK)으로 만든다. 순번이 999,999를 넘으면 자릿수가 자연히 늘어난다. */
    public static RecordId of(LocalDate date, long sequence) {
        return new RecordId(
                "MR-"
                        + DateTimeFormatter.BASIC_ISO_DATE.format(date)
                        + "-"
                        + String.format("%06d", sequence));
    }

    /** 저장된 문자열을 형식 검증과 함께 복원한다. */
    public static RecordId from(String value) {
        return new RecordId(value);
    }
}
