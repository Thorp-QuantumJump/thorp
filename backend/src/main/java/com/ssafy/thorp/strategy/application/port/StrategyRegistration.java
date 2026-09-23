package com.ssafy.thorp.strategy.application.port;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
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
 * @param active 활성 여부. 등록표에 적지 않으면 로더가 true로 읽는다. 비활성 전략은 카드에 보이지 않지만 등록표에서 사라지지는 않는다 — 과거 기록을
 *     해석하려면 그 시점에 쓰인 이름과 축 구성이 필요하다(research R5, 내부 계약 §2.3)
 * @param warmup 워밍업 산출 규칙. 없으면 워밍업 0이다(data-model §6)
 * @param targetWeightSource 목표 비중의 출처. {@code USER}면 화면이 002가 확정한 비중을 읽기 전용으로 보이고, {@code
 *     COMPUTED}면 FR-012의 자동 배분 설명을 보인다. 화면이 전략 ID로 분기하지 않게 하는 값이다
 */
public record StrategyRegistration(
        String strategyId,
        String name,
        String description,
        List<ParameterAxis> axes,
        boolean active,
        WarmupRule warmup,
        String targetWeightSource) {

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
        for (ParameterAxis axis : axes) {
            // 조건이 가리키는 축이 같은 전략에 없으면 그 조건은 영원히 거짓이라 축이 사라진 것과 같다. 기동에서 막는다.
            AxisCondition condition = axis.appliesWhen();
            if (condition != null && !seen.contains(condition.axis())) {
                throw new IllegalArgumentException(
                        "appliesWhen refers to an unknown axis: "
                                + strategyId
                                + "/"
                                + axis.name()
                                + " -> "
                                + condition.axis());
            }
        }
        axes = Collections.unmodifiableList(List.copyOf(axes));
    }

    /**
     * 지금 설정에서 뜻을 갖는 축만. 조건이 없는 축은 늘 들어가고, 조건이 있는 축은 결정 축의 값이 맞을 때만 들어간다(spec FR-003).
     *
     * <p>화면·접수 관문·조합 생성이 모두 이 하나로 판정한다. 각자 따로 판정하면 한쪽만 조건을 빠뜨려 화면이 계약대로 보낸 요청이 서버에서 막힌다.
     */
    public List<ParameterAxis> applicableAxes(Map<String, Object> settings) {
        return axes.stream().filter(axis -> axis.appliesTo(settings)).toList();
    }

    /** 설정에서 뜻을 갖는 축의 값만 남긴다. 조건 밖 축의 값은 사용자가 정한 것이 아니라 등록표 기본값이 딸려 온 것이라 실행에 싣지 않는다. */
    public Map<String, Object> applicableValues(Map<String, Object> settings) {
        Map<String, Object> kept = new LinkedHashMap<>();
        for (ParameterAxis axis : applicableAxes(settings)) {
            if (settings.containsKey(axis.name())) {
                kept.put(axis.name(), settings.get(axis.name()));
            }
        }
        return Collections.unmodifiableMap(kept);
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
