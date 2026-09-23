package com.ssafy.thorp.paramsearch.domain.vo;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 후보 하나가 쓰는 파라미터 조합. 축 이름 → 값이며 <b>값에 {@code null}을 허용하지 않는다.</b>
 *
 * <p>{@code null}을 막는 이유: 파라미터가 빠진 조합은 워커에서 기본값으로 조용히 대체되어 재현 기록(FR-020)이 실제 계산과 달라진다. 빈 조합은 허용한다 —
 * 파라미터가 없는 전략(예: 동일 비중 기준)이 있다.
 *
 * <p>축 순서를 보존한다({@code LinkedHashMap}). 등록표의 축 순서가 그리드 좌표의 뜻을 정하므로 순서가 흔들리면 좌표가 뜻을 잃는다.
 */
public record ParameterCombination(Map<String, Object> values) {

    public ParameterCombination {
        Objects.requireNonNull(values, "values must not be null");
        values.forEach(
                (axis, value) -> {
                    if (axis == null || axis.isBlank()) {
                        throw new IllegalArgumentException("axis name must not be blank");
                    }
                    if (value == null) {
                        throw new IllegalArgumentException(
                                "parameter value must not be null: axis=" + axis);
                    }
                });
        values = Collections.unmodifiableMap(new LinkedHashMap<>(values));
    }

    public static ParameterCombination of(Map<String, Object> values) {
        return new ParameterCombination(values);
    }

    public Object valueOf(String axis) {
        return values.get(axis);
    }

    public boolean isEmpty() {
        return values.isEmpty();
    }
}
