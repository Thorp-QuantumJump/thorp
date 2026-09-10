package com.ssafy.thorp.paramsearch.domain.model;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 묶음의 후보 집계(FR-008). 예상·유효·완료·실패·제외·미실행 여섯 값이다.
 *
 * <p>여섯 값이 함께 갱신되고 개별 필터·정렬 대상이 아니라 한 덩이(JSONB)로 저장한다(data-model.md §2).
 *
 * <p><b>제외와 미실행을 실패와 구분해서 센다</b>(원칙 VI). 계산하지 않은 후보를 실패로 합치면 "몇 건이 실제로 실패했는가"를 답할 수 없고, 낮은 수익과 구분되지
 * 않는다(FR-011).
 *
 * <ul>
 *   <li>{@code expected} — 전체 그리드 조합 수. 유효성 판정 전 수다
 *   <li>{@code valid} — 유효성 사전 판정을 통과해 계산 대상이 된 수
 *   <li>{@code completed} / {@code failed} — 계산을 시도한 결과
 *   <li>{@code excluded} — 유효성 위반으로 계산 전 제외(FR-006)
 *   <li>{@code skipped} — 취소·한도 도달로 발행되지 않음('미실행')
 * </ul>
 */
public record SearchCounts(
        long expected, long valid, long completed, long failed, long excluded, long skipped) {

    public SearchCounts {
        requireNotNegative(expected, "expected");
        requireNotNegative(valid, "valid");
        requireNotNegative(completed, "completed");
        requireNotNegative(failed, "failed");
        requireNotNegative(excluded, "excluded");
        requireNotNegative(skipped, "skipped");
    }

    /** 접수 직후 상태. 유효·제외 수는 유효성 판정에서 정해지고 나머지는 0에서 시작한다. */
    public static SearchCounts initial(long expected, long valid, long excluded) {
        return new SearchCounts(expected, valid, 0, 0, excluded, 0);
    }

    public static SearchCounts empty() {
        return new SearchCounts(0, 0, 0, 0, 0, 0);
    }

    /** 계산이 끝나기를 기다리는 후보 수. 음수가 되지 않도록 0에서 자른다. */
    public long pending() {
        return Math.max(0, valid - completed - failed - skipped);
    }

    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("expected", expected);
        map.put("valid", valid);
        map.put("completed", completed);
        map.put("failed", failed);
        map.put("excluded", excluded);
        map.put("skipped", skipped);
        return map;
    }

    public static SearchCounts fromMap(Map<String, Object> map) {
        Objects.requireNonNull(map, "counts map must not be null");
        return new SearchCounts(
                value(map, "expected"),
                value(map, "valid"),
                value(map, "completed"),
                value(map, "failed"),
                value(map, "excluded"),
                value(map, "skipped"));
    }

    private static long value(Map<String, Object> map, String key) {
        Object raw = map.get(key);
        if (!(raw instanceof Number n)) {
            throw new IllegalArgumentException("counts." + key + " must be a number");
        }
        return n.longValue();
    }

    private static void requireNotNegative(long value, String name) {
        if (value < 0) {
            throw new IllegalArgumentException(name + " must not be negative: " + value);
        }
    }
}
