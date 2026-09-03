package com.ssafy.thorp.strategy.domain.model;

import com.ssafy.thorp.strategy.domain.vo.ExecutionScope;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 유효한 전략 정의들의 모음. 식별자로 전략을 찾는 유일한 출처다(FR-001).
 *
 * <p>불변이며 기동 시 한 번 구성한다. 전략 정의는 배포 단위로 바뀌는 자산이라 실행 중에 갈아끼울 이유가 없고, 갈아끼울 수 있게 두면 같은 요청 처리 중에 카탈로그가
 * 바뀌는 경우를 모든 소비 지점이 감당해야 한다.
 *
 * <p>정의가 하나도 없는 카탈로그는 정상이다. 빈 목록을 반환할 뿐 오류가 아니다.
 *
 * <p>식별자가 겹치는 정의는 받지 않는다. {@link #find(String)}이 하나의 답을 가져야 하고, 겹쳤을 때 어느 쪽이 옳은지는 카탈로그가 알 수 없기 때문이다.
 * 다만 "충돌한 정의를 어떻게 처리할지"(둘 다 제외할지, 등재를 중단할지)는 정책이며 카탈로그를 구성하는 쪽이 정한다.
 */
public record StrategyCatalog(Map<String, StrategyDefinition> definitions) {

    public StrategyCatalog {
        Objects.requireNonNull(definitions, "카탈로그는 null일 수 없다");
        definitions = Collections.unmodifiableMap(new LinkedHashMap<>(definitions));
    }

    public static StrategyCatalog empty() {
        return new StrategyCatalog(Map.of());
    }

    /**
     * 정의 목록으로 카탈로그를 만든다.
     *
     * @throws IllegalArgumentException 식별자가 겹치는 정의가 있으면
     */
    public static StrategyCatalog of(Collection<StrategyDefinition> definitions) {
        Objects.requireNonNull(definitions, "정의 목록은 null일 수 없다");
        Map<String, StrategyDefinition> byId = new LinkedHashMap<>();
        for (StrategyDefinition definition : definitions) {
            StrategyDefinition previous = byId.put(definition.strategyId(), definition);
            if (previous != null) {
                throw new IllegalArgumentException("전략 식별자가 겹친다: " + definition.strategyId());
            }
        }
        return new StrategyCatalog(byId);
    }

    public Optional<StrategyDefinition> find(String strategyId) {
        return Optional.ofNullable(definitions.get(strategyId));
    }

    public boolean contains(String strategyId) {
        return definitions.containsKey(strategyId);
    }

    /** 등재된 모든 정의. 등재 순서를 유지한다. */
    public List<StrategyDefinition> all() {
        return List.copyOf(definitions.values());
    }

    /** 주어진 실행 목적을 지원하는 정의만 반환한다(FR-020). */
    public List<StrategyDefinition> supporting(ExecutionScope scope) {
        return definitions.values().stream().filter(it -> it.supports(scope)).toList();
    }

    public int size() {
        return definitions.size();
    }

    public boolean isEmpty() {
        return definitions.isEmpty();
    }
}
