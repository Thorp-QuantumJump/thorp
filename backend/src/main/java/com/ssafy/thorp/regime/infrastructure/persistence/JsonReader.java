package com.ssafy.thorp.regime.infrastructure.persistence;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;

/**
 * JSONB 칼럼에서 값을 꺼내는 자리 하나.
 *
 * <p>여기 있는 것은 <b>표기를 바꾸는 일뿐</b>이다 — 더하거나 비교하거나 반올림하지 않는다. decimal은 {@code new BigDecimal(String)}으로
 * 읽어 자릿수를 그대로 보존한다. {@code Double}로 한 번이라도 지나가면 배치가 12자리로 고정해 둔 값이 조용히 달라지고, 그 어긋남은 화면의 마지막 자리에서만
 * 드러난다(research R4).
 */
final class JsonReader {

    private JsonReader() {}

    @SuppressWarnings("unchecked")
    static Map<String, Object> object(Map<String, Object> source, String key) {
        Object value = source == null ? null : source.get(key);
        return value instanceof Map ? (Map<String, Object>) value : Map.of();
    }

    @SuppressWarnings("unchecked")
    static List<Object> array(Map<String, Object> source, String key) {
        Object value = source == null ? null : source.get(key);
        return value instanceof List ? (List<Object>) value : List.of();
    }

    static String text(Map<String, Object> source, String key) {
        Object value = source == null ? null : source.get(key);
        return value == null ? null : String.valueOf(value);
    }

    static int integer(Map<String, Object> source, String key) {
        Object value = source == null ? null : source.get(key);
        if (value instanceof Number number) {
            return number.intValue();
        }
        // 수를 문자열로 담아 둔 자리도 받아 준다. 없으면 0이 아니라 실패여야 하지만, 집계 수는
        // 배치가 늘 채우므로 여기서 형만 맞춘다.
        return value == null ? 0 : Integer.parseInt(String.valueOf(value));
    }

    /** decimal은 문자열로 저장돼 있다. 없으면 {@code null} — <b>0으로 채우지 않는다</b>. */
    static BigDecimal decimal(Map<String, Object> source, String key) {
        String value = text(source, key);
        return value == null ? null : new BigDecimal(value);
    }

    static BigDecimal decimal(Object value) {
        return value == null ? null : new BigDecimal(String.valueOf(value));
    }

    static List<BigDecimal> decimals(Map<String, Object> source, String key) {
        return array(source, key).stream().map(JsonReader::decimal).toList();
    }

    static YearMonth month(Map<String, Object> source, String key) {
        String value = text(source, key);
        return value == null ? null : YearMonth.parse(value);
    }

    static YearMonth month(Object value) {
        return value == null ? null : YearMonth.parse(String.valueOf(value));
    }

    static List<YearMonth> months(Map<String, Object> source, String key) {
        return array(source, key).stream().map(JsonReader::month).toList();
    }

    static LocalDate date(Map<String, Object> source, String key) {
        String value = text(source, key);
        return value == null ? null : LocalDate.parse(value);
    }

    static YearMonth toMonth(LocalDate date) {
        return date == null ? null : YearMonth.from(date);
    }
}
