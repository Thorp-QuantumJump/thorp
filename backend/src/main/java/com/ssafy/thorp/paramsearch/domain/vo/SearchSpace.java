package com.ssafy.thorp.paramsearch.domain.vo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 탐색 공간. <b>전략 → 축 → 값</b>의 중첩 구조이며 접수 시점에 고정되어 재현 기록으로 남는다(FR-020, FR-028).
 *
 * <p>전략별로 축을 따로 담는 이유: 한 묶음이 전략 여러 개를 담을 수 있고 전략마다 축 구성이 다르다. 축을 묶음 전체에서 평면으로 두면 서로 다른 전략의 같은 이름 축이
 * 충돌한다(data-model.md §2.1).
 *
 * <p>축을 {@code Map}이 아니라 <b>순서 있는 목록</b>으로 담는다. 저장은 {@code jsonb}인데 객체 키 순서가 보존되지 않으므로, 축을 객체 키로 두면
 * {@link GridCoordinates}의 인덱스가 가리키는 대상이 흔들린다. 각 축 {@code values}의 순서 또한 그 좌표의 뜻이다(FR-029).
 *
 * <p>값은 등록표가 돌려준 후보값을 그대로 담는다 — 정렬·보간·단위 변환을 하지 않는다(FR-025). {@code strategyName}·{@code
 * label}·{@code unit}을 함께 남기는 이유는 등록표 버전이 올라가 표시 문구가 바뀐 뒤에도 그 묶음이 무엇을 탐색했는지 읽을 수 있어야 하기 때문이다(원칙
 * VIII).
 *
 * <p>{@link #combinationCount()}가 전체 그리드 크기다 — <b>전략별 (축별 값 개수의 곱)의 합</b>이며, 접수 전 작업량 확인(FR-005a)과
 * 상한 초과 판정(FR-005b)이 이 값을 쓴다. 유효성 사전 제외 전의 수이므로 <b>유효 후보 수와는 다르다.</b>
 */
public record SearchSpace(Map<String, StrategySpace> strategies) {

    public SearchSpace {
        Objects.requireNonNull(strategies, "strategies must not be null");
        Map<String, StrategySpace> copy = new LinkedHashMap<>();
        strategies.forEach(
                (strategyId, space) -> {
                    if (strategyId == null || strategyId.isBlank()) {
                        throw new IllegalArgumentException("strategyId must not be blank");
                    }
                    if (space == null) {
                        throw new IllegalArgumentException(
                                "strategy space must not be null: strategyId=" + strategyId);
                    }
                    copy.put(strategyId, space);
                });
        strategies = Collections.unmodifiableMap(copy);
    }

    public static SearchSpace of(Map<String, StrategySpace> strategies) {
        return new SearchSpace(strategies);
    }

    /** 탐색 대상 전략. 등록표가 돌려준 순서를 보존한다. */
    public List<String> strategyIds() {
        return List.copyOf(strategies.keySet());
    }

    public StrategySpace strategySpace(String strategyId) {
        StrategySpace space = strategies.get(strategyId);
        if (space == null) {
            throw new IllegalArgumentException("unknown strategy: " + strategyId);
        }
        return space;
    }

    public List<String> axisNames(String strategyId) {
        return strategySpace(strategyId).axisNames();
    }

    public List<Object> valuesOf(String strategyId, String axisName) {
        return strategySpace(strategyId).valuesOf(axisName);
    }

    /** 전략이 하나도 없다 — 조합 생성 전에 거부된 묶음이 여기 해당한다(FR-026). */
    public boolean isEmpty() {
        return strategies.isEmpty();
    }

    /**
     * 전체 그리드 크기 — 전략별 조합 수의 합이다. 전략이 없으면 0이고, 축이 없는 전략은 1을 더한다(파라미터가 없는 전략도 조합 1건이다).
     *
     * <p>{@code long}으로 셈하고 넘침을 예외로 드러낸다. 조용히 음수로 넘치면 상한 초과 판정이 통과해 버린다.
     */
    public long combinationCount() {
        long total = 0L;
        for (StrategySpace space : strategies.values()) {
            long next = total + space.combinationCount();
            if (next < total) {
                throw new ArithmeticException(
                        "search space size overflows long: strategies=" + strategies.keySet());
            }
            total = next;
        }
        return total;
    }

    /**
     * 전략 하나의 탐색 공간. 축 목록의 순서를 보존한다.
     *
     * <p>{@code strategyName}은 표시용 기록이며 {@code null}을 허용한다 — 007은 전략명을 만들지 않고 등록표가 준 값을 옮길
     * 뿐이다(FR-025).
     */
    public record StrategySpace(String strategyName, List<Axis> axes) {

        public StrategySpace {
            Objects.requireNonNull(axes, "axes must not be null");
            Set<String> seen = new LinkedHashSet<>();
            for (Axis axis : axes) {
                if (axis == null) {
                    throw new IllegalArgumentException("axis must not be null");
                }
                if (!seen.add(axis.name())) {
                    throw new IllegalArgumentException("duplicate axis: " + axis.name());
                }
            }
            axes = Collections.unmodifiableList(new ArrayList<>(axes));
        }

        public static StrategySpace of(String strategyName, List<Axis> axes) {
            return new StrategySpace(strategyName, axes);
        }

        public List<String> axisNames() {
            return axes.stream().map(Axis::name).toList();
        }

        public Axis axis(String axisName) {
            return axes.stream()
                    .filter(axis -> axis.name().equals(axisName))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("unknown axis: " + axisName));
        }

        public List<Object> valuesOf(String axisName) {
            return axis(axisName).values();
        }

        /** 축별 값 개수의 곱. 축이 없으면 1이다. */
        public long combinationCount() {
            long count = 1L;
            for (Axis axis : axes) {
                int size = axis.values().size();
                long next = count * size;
                if (next / size != count) {
                    throw new ArithmeticException(
                            "strategy space size overflows long: axes=" + axisNames());
                }
                count = next;
            }
            return count;
        }
    }

    /**
     * 축 하나와 그 축에서 실제 탐색한 후보값.
     *
     * <p>{@code values}의 인덱스가 곧 {@link GridCoordinates}의 인덱스다. {@code label}·{@code unit}은 표시용 기록이며
     * {@code null}을 허용한다.
     */
    public record Axis(String name, String label, String unit, List<Object> values) {

        public Axis {
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("axis name must not be blank");
            }
            Objects.requireNonNull(values, "axis values must not be null");
            if (values.isEmpty()) {
                throw new IllegalArgumentException(
                        "axis must have at least one value: axis=" + name);
            }
            // contains(null)을 쓰지 않는다 — List.of()가 만드는 불변 리스트는 null 인자에
            // NPE를 던져서 null 검사 자체가 터진다.
            if (values.stream().anyMatch(Objects::isNull)) {
                throw new IllegalArgumentException(
                        "axis values must not contain null: axis=" + name);
            }
            if (Set.copyOf(values).size() != values.size()) {
                throw new IllegalArgumentException(
                        "axis values must not contain duplicates: axis=" + name);
            }
            values = Collections.unmodifiableList(new ArrayList<>(values));
        }

        public static Axis of(String name, String label, String unit, List<Object> values) {
            return new Axis(name, label, unit, values);
        }

        /** 표시 정보 없이 축과 값만 기록할 때 쓴다 — 테스트와 표시 정보가 없는 등록표용이다. */
        public static Axis of(String name, List<Object> values) {
            return new Axis(name, null, null, values);
        }
    }
}
