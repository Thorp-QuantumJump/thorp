package com.ssafy.thorp.paramsearch.registry;

import com.ssafy.thorp.paramsearch.application.port.AllowedRange;
import com.ssafy.thorp.paramsearch.application.port.ParameterAxis;
import com.ssafy.thorp.paramsearch.application.port.ParameterRegistryPort;
import com.ssafy.thorp.paramsearch.application.port.SearchContext;
import com.ssafy.thorp.paramsearch.application.port.StrategyExecutionSupportPort;
import com.ssafy.thorp.paramsearch.application.port.StrategyRegistration;
import com.ssafy.thorp.paramsearch.application.port.ValidationVerdict;
import com.ssafy.thorp.paramsearch.domain.vo.ParameterCombination;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiPredicate;

/**
 * 테스트용 등록표 fixture(T127).
 *
 * <p>전략 수·축 수·축별 후보 개수를 자유롭게 구성한다. <b>운영 코드가 아니므로 전략 값을 직접 담는다</b> — FR-025가 금지하는 것은 007 운영 코드의
 * 하드코딩이고, 등록표를 흉내 내는 fixture는 그 예외다.
 *
 * <p>여기에 실제 전략 ID를 쓰지 않는 이유는 따로 있다. 실제 이름을 쓰면 "그 전략이라서 되는 것"과 "어떤 전략이든 되는 것"을 구분할 수 없다. 합성 이름을 쓰면
 * 전략을 더하거나 빼는 검증(SC-010~SC-012)이 그대로 성립한다.
 */
public final class RegistryFixtures {

    private RegistryFixtures() {}

    public static Builder registry(String version) {
        return new Builder(version);
    }

    /** 축 하나. 후보값의 순서가 그리드 좌표의 뜻이다. */
    public static ParameterAxis axis(String name, List<Object> searchValues) {
        return new ParameterAxis(
                name,
                name + " 라벨",
                null,
                searchValues,
                searchValues.get(0),
                AllowedRange.ofValues(searchValues));
    }

    /** 후보값이 {@code count}개인 축. 값은 0부터 센 정수다. */
    public static ParameterAxis axisOfSize(String name, int count) {
        List<Object> values = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            values.add(i);
        }
        return axis(name, values);
    }

    /** 004가 실행을 지원한다고 답하는 fixture. */
    public static StrategyExecutionSupportPort supporting(String... strategyIds) {
        Set<String> supported = Set.of(strategyIds);
        return () -> supported;
    }

    /** 조회가 실패하는 등록표. 503 경로(FR-028, research R9) 검증용이다. */
    public static ParameterRegistryPort unavailable() {
        return new FixtureRegistry(null, List.of(), Map.of(), null) {
            @Override
            public Optional<StrategyRegistration> find(String strategyId) {
                throw new IllegalStateException("registry down");
            }

            @Override
            public List<StrategyRegistration> activeStrategies() {
                throw new IllegalStateException("registry down");
            }
        };
    }

    /** 유효성 판정만 실패하는 등록표. 판정을 못 받은 조합을 유효로 넘기지 않는지 보는 데 쓴다. */
    public static ParameterRegistryPort validationUnavailable() {
        return new FixtureRegistry("FIXTURE-BROKEN", List.of(), Map.of(), null) {
            @Override
            public ValidationVerdict validate(
                    String strategyId, ParameterCombination combination, SearchContext context) {
                throw new IllegalStateException("registry down");
            }
        };
    }

    public static final class Builder {

        private final String version;
        private final List<StrategyRegistration> strategies = new ArrayList<>();
        private final Map<String, List<FixtureRule>> rules = new LinkedHashMap<>();
        private String baselineStrategyId;

        private Builder(String version) {
            this.version = version;
        }

        public Builder strategy(String strategyId, ParameterAxis... axes) {
            strategies.add(
                    new StrategyRegistration(strategyId, strategyId + " 이름", null, List.of(axes)));
            rules.computeIfAbsent(strategyId, key -> new ArrayList<>());
            return this;
        }

        /** 유효성 규칙. 조건이 참이면 위반이다 — 등록표가 판정을 소유한다는 계약을 fixture도 그대로 따른다. */
        public Builder rule(
                String strategyId,
                String reasonCode,
                BiPredicate<ParameterCombination, SearchContext> violated) {
            rules.computeIfAbsent(strategyId, key -> new ArrayList<>())
                    .add(new FixtureRule(reasonCode, violated));
            return this;
        }

        public Builder baseline(String strategyId) {
            this.baselineStrategyId = strategyId;
            return this;
        }

        public ParameterRegistryPort build() {
            return new FixtureRegistry(
                    version, List.copyOf(strategies), Map.copyOf(rules), baselineStrategyId);
        }
    }

    private record FixtureRule(
            String reasonCode, BiPredicate<ParameterCombination, SearchContext> violated) {}

    private static class FixtureRegistry implements ParameterRegistryPort {

        private final String version;
        private final List<StrategyRegistration> strategies;
        private final Map<String, List<FixtureRule>> rules;
        private final String baselineStrategyId;

        private FixtureRegistry(
                String version,
                List<StrategyRegistration> strategies,
                Map<String, List<FixtureRule>> rules,
                String baselineStrategyId) {
            this.version = version;
            this.strategies = strategies;
            this.rules = rules;
            this.baselineStrategyId = baselineStrategyId;
        }

        @Override
        public String version() {
            return version;
        }

        @Override
        public List<StrategyRegistration> activeStrategies() {
            return strategies;
        }

        @Override
        public Optional<StrategyRegistration> find(String strategyId) {
            return strategies.stream()
                    .filter(strategy -> strategy.strategyId().equals(strategyId))
                    .findFirst();
        }

        @Override
        public ValidationVerdict validate(
                String strategyId, ParameterCombination combination, SearchContext context) {
            for (FixtureRule rule : rules.getOrDefault(strategyId, List.of())) {
                if (rule.violated().test(combination, context)) {
                    return ValidationVerdict.violation(
                            rule.reasonCode(), rule.reasonCode() + " 위반입니다");
                }
            }
            return ValidationVerdict.ok();
        }

        @Override
        public Optional<String> baselineStrategyId() {
            return Optional.ofNullable(baselineStrategyId);
        }
    }
}
