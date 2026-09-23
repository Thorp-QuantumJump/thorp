package com.ssafy.thorp.paramsearch.domain.vo;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 후보의 그리드 좌표. 축 이름 → 등록표에서의 값 인덱스(0부터)다.
 *
 * <p><b>인접 안정성 판정의 근거다</b>(research R7, FR-013a). 이웃은 "한 축의 인덱스만 ±1 다른 후보"다. 값 자체(예: 관측 기간 60일)를
 * 비교하지 않는 이유는 간격이 불균등한 축에서 "한 칸"의 뜻이 흔들리기 때문이다 — 등록표 인덱스는 그 뜻을 그대로 담는다.
 *
 * <p>좌표는 재현 기록(FR-020)의 일부이기도 하다. 인덱스가 뜻을 가지려면 같은 {@code registryVersion} 안에서 축 순서와 값 순서가 안정적이어야
 * 한다.
 */
public record GridCoordinates(Map<String, Integer> indices) {

    public GridCoordinates {
        Objects.requireNonNull(indices, "indices must not be null");
        indices.forEach(
                (axis, index) -> {
                    if (axis == null || axis.isBlank()) {
                        throw new IllegalArgumentException("axis name must not be blank");
                    }
                    if (index == null || index < 0) {
                        throw new IllegalArgumentException(
                                "grid index must not be negative: axis="
                                        + axis
                                        + ", index="
                                        + index);
                    }
                });
        indices = Collections.unmodifiableMap(new LinkedHashMap<>(indices));
    }

    public static GridCoordinates of(Map<String, Integer> indices) {
        return new GridCoordinates(indices);
    }

    /**
     * {@code other}가 이웃인가 — 축 집합이 같고 정확히 한 축의 인덱스만 1 다르다.
     *
     * <p>축 집합이 다르면 이웃이 아니다. 같은 묶음의 후보라면 축 집합이 같지만, 전략이 섞인 묶음에서는 축이 갈릴 수 있어 여기서 걸러 둔다.
     */
    public boolean isNeighborOf(GridCoordinates other) {
        if (other == null || !indices.keySet().equals(other.indices.keySet())) {
            return false;
        }
        int differingAxes = 0;
        for (Map.Entry<String, Integer> entry : indices.entrySet()) {
            int distance = Math.abs(entry.getValue() - other.indices.get(entry.getKey()));
            if (distance > 1) {
                return false;
            }
            differingAxes += distance;
            if (differingAxes > 1) {
                return false;
            }
        }
        return differingAxes == 1;
    }
}
