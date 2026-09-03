package com.ssafy.thorp.strategy.domain.vo;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * 기본값이 모두 채워진 확정 파라미터 값 집합(FR-014).
 *
 * <p>"확정"은 그 실행에서 전략이 사용하는 파라미터의 값이 빠짐없이 들어 있다는 뜻이다. 사용자가 명시한 값과 기본값에서 온 값을 구분하지 않는다. 실행 기록이 이 집합을
 * 그대로 보존하므로 나중에 같은 조건을 재현할 수 있다(SC-009).
 *
 * <p>전략마다 이름과 개수가 다르므로 값은 이름을 키로 하는 맵으로 담는다. 값의 종류는 {@link ParameterValue}가 닫아 두므로 맵이라도 타입이 열려 있지
 * 않다.
 */
public record ResolvedParameters(Map<String, ParameterValue> values) {

    public ResolvedParameters {
        Objects.requireNonNull(values, "확정 값 집합은 null일 수 없다");
        values.forEach(
                (name, value) -> {
                    Objects.requireNonNull(name, "파라미터 이름은 null일 수 없다");
                    Objects.requireNonNull(value, "파라미터 값은 null일 수 없다: name=" + name);
                });
        // 선언 순서를 유지한 불변 복사본. 위반 보고와 로그의 순서가 소비 지점마다 달라지지 않게 한다.
        values = Collections.unmodifiableMap(new LinkedHashMap<>(values));
    }

    public static ResolvedParameters empty() {
        return new ResolvedParameters(Map.of());
    }

    public Optional<ParameterValue> get(String name) {
        return Optional.ofNullable(values.get(name));
    }

    public boolean contains(String name) {
        return values.containsKey(name);
    }

    public Set<String> names() {
        return values.keySet();
    }

    public int size() {
        return values.size();
    }

    public boolean isEmpty() {
        return values.isEmpty();
    }
}
