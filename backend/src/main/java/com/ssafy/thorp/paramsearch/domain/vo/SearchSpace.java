package com.ssafy.thorp.paramsearch.domain.vo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 탐색 공간. 축 이름 → 사용자가 선택한 후보 값 목록이다. 접수 시점에 고정되어 재현 기록으로 남는다(FR-020).
 *
 * <p>축 순서와 각 축의 값 순서를 보존한다. 그 순서가 {@link GridCoordinates}의 인덱스가 가리키는 대상이므로, 순서가 흔들리면 저장된 좌표가 다른 값을
 * 가리킨다.
 *
 * <p>{@link #combinationCount()}가 전체 그리드 크기다 — 축별 값 개수의 곱이며, 접수 전 작업량 확인(FR-005a)과 상한 초과
 * 판정(FR-005b)이 이 값을 쓴다. 유효성 사전 제외 전의 수이므로 <b>유효 후보 수와는 다르다.</b>
 */
public record SearchSpace(Map<String, List<Object>> axes) {

    public SearchSpace {
        Objects.requireNonNull(axes, "axes must not be null");
        Map<String, List<Object>> copy = new LinkedHashMap<>();
        axes.forEach(
                (axis, values) -> {
                    if (axis == null || axis.isBlank()) {
                        throw new IllegalArgumentException("axis name must not be blank");
                    }
                    if (values == null || values.isEmpty()) {
                        throw new IllegalArgumentException(
                                "axis must have at least one value: axis=" + axis);
                    }
                    // contains(null)을 쓰지 않는다 — List.of()가 만드는 불변 리스트는 null 인자에
                    // NPE를 던져서 null 검사 자체가 터진다.
                    if (values.stream().anyMatch(Objects::isNull)) {
                        throw new IllegalArgumentException(
                                "axis values must not contain null: axis=" + axis);
                    }
                    if (Set.copyOf(values).size() != values.size()) {
                        throw new IllegalArgumentException(
                                "axis values must not contain duplicates: axis=" + axis);
                    }
                    copy.put(axis, Collections.unmodifiableList(new ArrayList<>(values)));
                });
        axes = Collections.unmodifiableMap(copy);
    }

    public static SearchSpace of(Map<String, List<Object>> axes) {
        return new SearchSpace(axes);
    }

    public List<String> axisNames() {
        return List.copyOf(axes.keySet());
    }

    public List<Object> valuesOf(String axis) {
        List<Object> values = axes.get(axis);
        if (values == null) {
            throw new IllegalArgumentException("unknown axis: " + axis);
        }
        return values;
    }

    public boolean isEmpty() {
        return axes.isEmpty();
    }

    /**
     * 전체 그리드 크기(축별 값 개수의 곱). 축이 없으면 1이다 — 파라미터가 없는 전략도 조합 1건이다.
     *
     * <p>{@code long}으로 셈한다. 축이 늘어나면 곱이 {@code int} 범위를 넘을 수 있고, 그때 조용히 음수로 넘치면 상한 초과 판정이 통과해 버린다.
     * 넘침은 여기서 예외로 드러낸다.
     */
    public long combinationCount() {
        long count = 1L;
        for (List<Object> values : axes.values()) {
            long next = count * values.size();
            if (values.size() != 0 && next / values.size() != count) {
                throw new ArithmeticException(
                        "search space size overflows long: axes=" + axes.keySet());
            }
            count = next;
        }
        return count;
    }
}
