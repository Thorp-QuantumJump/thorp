package com.ssafy.thorp.regimelink.infrastructure.persistence;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * JSONB 컬럼에서 값을 꺼낼 때 쓰는 읽기 도우미.
 *
 * <p><b>없는 값을 0이나 빈 문자열로 채우지 않는다.</b> 저장된 JSON 에 키가 없다는 것은 그 값이 없었다는 뜻이고, 기본값으로 메우면 판정이 실제와 다른 전제
 * 위에 서게 된다.
 */
final class JsonValues {

    private JsonValues() {}

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> objects(Map<String, Object> source, String key) {
        Object value = source == null ? null : source.get(key);
        return value instanceof List ? (List<Map<String, Object>>) value : List.of();
    }

    static String text(Map<String, Object> source, String key) {
        Object value = source == null ? null : source.get(key);
        return value == null ? null : String.valueOf(value);
    }

    static Integer integer(Map<String, Object> source, String key) {
        Object value = source == null ? null : source.get(key);
        if (value == null) {
            return null;
        }
        return value instanceof Number number
                ? number.intValue()
                : Integer.valueOf(String.valueOf(value));
    }

    static boolean flag(Map<String, Object> source, String key) {
        Object value = source == null ? null : source.get(key);
        return value instanceof Boolean bool ? bool : Boolean.parseBoolean(String.valueOf(value));
    }

    static LocalDate date(Map<String, Object> source, String key) {
        String value = text(source, key);
        return value == null ? null : LocalDate.parse(value);
    }

    static YearMonth month(Map<String, Object> source, String key) {
        String value = text(source, key);
        return value == null ? null : YearMonth.parse(value);
    }

    /** decimal 은 문자열로 저장돼 있다. 표기를 보존하며 읽는다. */
    static BigDecimal decimal(Map<String, Object> source, String key) {
        String value = text(source, key);
        return value == null ? null : new BigDecimal(value);
    }

    /** {@code null} 값은 키째로 빼고 담는다 — 저장 규칙이 "키 없음 == null" 이다. */
    static void put(Map<String, Object> target, String key, Object value) {
        if (value != null) {
            target.put(
                    key,
                    value instanceof LocalDate || value instanceof YearMonth
                            ? value.toString()
                            : value);
        }
    }

    static Map<String, Object> map() {
        return new LinkedHashMap<>();
    }
}
