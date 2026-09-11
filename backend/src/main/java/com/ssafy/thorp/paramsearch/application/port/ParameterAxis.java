package com.ssafy.thorp.paramsearch.application.port;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * 전략 파라미터 축 하나의 등록 정보(contracts/param-search-internal.md §1).
 *
 * <p><b>{@code searchValues}의 인덱스가 그리드 좌표다.</b> 같은 등록표 버전 안에서 이 순서가 안정적이어야 저장된 {@code
 * grid_coordinates}가 뜻을 유지하고 인접 판정이 성립한다(FR-029, research R7).
 *
 * <p>007은 값을 해석해 순서를 만들지 않는다 — 그래서 숫자가 아닌 축(범주형)도 전략별 분기 없이 같은 규칙으로 다뤄진다.
 *
 * @param name 축 이름. 조합과 좌표의 키다
 * @param label 표시 문구. 007이 만들지 않는다
 * @param unit 단위. 없으면 null
 * @param searchValues 자동 탐색 후보값. 인덱스가 그리드 좌표다
 * @param defaultValue 기본 시작값. 007이 정하지 않으며 추천이나 최적값으로 표시하지 않는다(원칙 III)
 * @param allowedRange 입력 허용 범위. 상세 변경의 경계다
 */
public record ParameterAxis(
        String name,
        String label,
        String unit,
        List<Object> searchValues,
        Object defaultValue,
        AllowedRange allowedRange) {

    public ParameterAxis {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("axis name must not be blank");
        }
        Objects.requireNonNull(searchValues, "searchValues must not be null");
        if (searchValues.isEmpty()) {
            throw new IllegalArgumentException("axis must have at least one search value: " + name);
        }
        if (searchValues.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("search values must not contain null: " + name);
        }
        searchValues = Collections.unmodifiableList(List.copyOf(searchValues));
    }

    /**
     * 값의 그리드 좌표. 등록표 목록에 없는 값이면 -1이다.
     *
     * <p>값 비교는 {@code equals}만 쓴다 — 007이 형 변환이나 근사 비교를 하면 등록표에 없는 값을 만들어 내는 셈이다(FR-002).
     */
    public int indexOf(Object value) {
        return searchValues.indexOf(value);
    }

    public int valueCount() {
        return searchValues.size();
    }
}
