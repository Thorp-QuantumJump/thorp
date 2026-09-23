package com.ssafy.thorp.regimelink.domain.vo;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 검색 경로별 연결 기록의 외부 식별자 {@code RLS-YYYYMMDD-NNNNNN} (data-model §3).
 *
 * <p><b>010 API의 {@code searchId}(snapshot 키)와 다른 값이다.</b> 계약에서 이 값의 이름은 {@code linkSearchId}이며, 둘을
 * 같은 이름으로 부르면 화면이 어느 것을 고정했는지 알 수 없게 된다(contracts §0).
 */
public record LinkSearchId(String value) {

    private static final Pattern PATTERN = Pattern.compile("^RLS-\\d{8}-\\d{6,}$");

    public LinkSearchId {
        Objects.requireNonNull(value, "linkSearchId must not be null");
        if (!PATTERN.matcher(value).matches()) {
            throw new IllegalArgumentException("invalid linkSearchId format: " + value);
        }
    }

    public static LinkSearchId of(LocalDate date, long sequence) {
        return new LinkSearchId(
                "RLS-"
                        + DateTimeFormatter.BASIC_ISO_DATE.format(date)
                        + "-"
                        + String.format("%06d", sequence));
    }

    public static LinkSearchId from(String value) {
        return new LinkSearchId(value);
    }
}
