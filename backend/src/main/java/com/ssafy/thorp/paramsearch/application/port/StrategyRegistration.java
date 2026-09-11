package com.ssafy.thorp.paramsearch.application.port;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 전략 하나의 등록 정보(contracts/param-search-internal.md §1).
 *
 * <p>007이 전략에 대해 아는 것은 이 record가 담은 것이 전부다. 전략 이름·축 구성·후보값·기본값·허용 범위는 모두 003 등록표가 소유하며 007은 옮길
 * 뿐이다(FR-025).
 *
 * @param strategyId 등록표의 전략 식별자
 * @param name 표시용 전략명. 007이 만들지 않는다
 * @param description 표시용 설명. 없으면 null
 * @param axes 파라미터 축. <b>순서를 보존한다.</b> 파라미터가 없는 전략은 빈 목록이고 조합 1건이다
 */
public record StrategyRegistration(
        String strategyId, String name, String description, List<ParameterAxis> axes) {

    public StrategyRegistration {
        if (strategyId == null || strategyId.isBlank()) {
            throw new IllegalArgumentException("strategyId must not be blank");
        }
        if (axes == null) {
            throw new IllegalArgumentException("axes must not be null: " + strategyId);
        }
        Set<String> seen = new LinkedHashSet<>();
        for (ParameterAxis axis : axes) {
            if (axis == null) {
                throw new IllegalArgumentException("axis must not be null: " + strategyId);
            }
            if (!seen.add(axis.name())) {
                throw new IllegalArgumentException(
                        "duplicate axis in registration: " + strategyId + "/" + axis.name());
            }
        }
        axes = Collections.unmodifiableList(List.copyOf(axes));
    }

    public List<String> axisNames() {
        return axes.stream().map(ParameterAxis::name).toList();
    }

    public Optional<ParameterAxis> axis(String axisName) {
        return axes.stream().filter(axis -> axis.name().equals(axisName)).findFirst();
    }

    /** 이 전략이 만드는 조합 수 — 축별 후보 개수의 곱이다. 축이 없으면 1이다(FR-027). */
    public long combinationCount() {
        long count = 1L;
        for (ParameterAxis axis : axes) {
            long next = count * axis.valueCount();
            if (next / axis.valueCount() != count) {
                throw new ArithmeticException(
                        "registration grid overflows long: strategyId=" + strategyId);
            }
            count = next;
        }
        return count;
    }
}
