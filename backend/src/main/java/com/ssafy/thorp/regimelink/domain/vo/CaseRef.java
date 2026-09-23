package com.ssafy.thorp.regimelink.domain.vo;

import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.Objects;

/**
 * 사례 식별자 = (010 snapshot 키, 관측 종료월) (research R6).
 *
 * <p>주소와 계약에서는 {@code {snapshotKey}~{endMonth}} 한 값으로 다닌다. {@code snapshotKey}에는 {@code ~}가 없고
 * {@code :}·{@code /}는 키 안에 쓰이므로 구분자로 {@code ~}를 골랐다. <b>011은 snapshot 키를 분해하지 않는다</b> — 조각의 뜻은
 * 010이 소유한다.
 */
public record CaseRef(String snapshotKey, YearMonth endMonth) {

    private static final char DELIMITER = '~';

    public CaseRef {
        if (snapshotKey == null || snapshotKey.isBlank()) {
            throw new IllegalArgumentException("snapshotKey 가 비어 있습니다.");
        }
        if (snapshotKey.indexOf(DELIMITER) >= 0) {
            throw new IllegalArgumentException("snapshotKey 에 구분자 '~' 가 있습니다: " + snapshotKey);
        }
        Objects.requireNonNull(endMonth, "endMonth must not be null");
    }

    /**
     * {@code caseId} 문자열을 되돌린다. 모양이 다르면 {@link IllegalArgumentException}이고 호출자가 400으로 바꾼다.
     *
     * <p><b>마지막 {@code ~}로 나눈다</b> — snapshot 키에는 구분자가 없지만 앞에서 나누면 키가 잘릴 여지를 남긴다(contracts §0).
     */
    public static CaseRef parse(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("caseId 가 비어 있습니다.");
        }
        int at = value.lastIndexOf(DELIMITER);
        if (at <= 0 || at == value.length() - 1) {
            throw new IllegalArgumentException(
                    "caseId 는 '{snapshotKey}~{endMonth}' 여야 합니다: " + value);
        }
        try {
            return new CaseRef(value.substring(0, at), YearMonth.parse(value.substring(at + 1)));
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("caseId 의 종료월을 읽을 수 없습니다: " + value, e);
        }
    }

    @Override
    public String toString() {
        return snapshotKey + DELIMITER + endMonth;
    }
}
