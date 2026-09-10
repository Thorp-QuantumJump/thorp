package com.ssafy.thorp.paramsearch.domain.vo;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 탐색 묶음 외부 식별자. 형식은 {@code PS-{yyyyMMdd}-{6자리 이상 순번}}이며 DB PK(Long)와 구분된다.
 *
 * <p>{@code BacktestId}와 같은 규약을 쓴다 — 화면·API에 노출되는 값은 이것이고 PK는 나가지 않는다. 사용자에게 식별자 입력을 요구하지 않는다(원칙
 * VIII).
 */
public record ParamSearchId(String value) {

    private static final Pattern PATTERN = Pattern.compile("^PS-\\d{8}-\\d{6,}$");

    public ParamSearchId {
        Objects.requireNonNull(value, "paramSearchId must not be null");
        if (!PATTERN.matcher(value).matches()) {
            throw new IllegalArgumentException("invalid paramSearchId format: " + value);
        }
    }

    /** 날짜와 순번(PK)으로 만든다. 순번이 999,999를 넘으면 자릿수가 자연히 늘어난다. */
    public static ParamSearchId of(LocalDate date, long sequence) {
        return new ParamSearchId(
                "PS-"
                        + DateTimeFormatter.BASIC_ISO_DATE.format(date)
                        + "-"
                        + String.format("%06d", sequence));
    }

    /** 저장된 문자열을 형식 검증과 함께 복원한다. */
    public static ParamSearchId from(String value) {
        return new ParamSearchId(value);
    }
}
