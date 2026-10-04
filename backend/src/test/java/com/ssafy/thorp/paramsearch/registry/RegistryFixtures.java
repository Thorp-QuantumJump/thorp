package com.ssafy.thorp.paramsearch.registry;

import com.ssafy.thorp.paramsearch.application.port.StrategyExecutionSupportPort;
import com.ssafy.thorp.strategy.application.port.AllowedRange;
import com.ssafy.thorp.strategy.application.port.AnalysisCondition;
import com.ssafy.thorp.strategy.application.port.AnalysisConditionRegistry;
import com.ssafy.thorp.strategy.application.port.ParameterAxis;
import com.ssafy.thorp.strategy.application.port.ParameterRegistryPort;
import com.ssafy.thorp.strategy.application.port.SearchContext;
import com.ssafy.thorp.strategy.application.port.StrategyRegistration;
import com.ssafy.thorp.strategy.application.port.ValidationRule;
import com.ssafy.thorp.strategy.application.port.ValidationVerdict;
import com.ssafy.thorp.strategy.application.port.WarmupRule;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiPredicate;
import java.util.function.UnaryOperator;

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

    private static final String DEFAULT_TARGET_WEIGHT_SOURCE = "COMPUTED";

    /** 분석 조건은 007 검증의 대상이 아니다. 포트가 null을 돌려주지 않도록 최소값만 채운다. */
    private static final AnalysisConditionRegistry FIXTURE_CONDITIONS =
            new AnalysisConditionRegistry(
                    new AnalysisCondition(AllowedRange.between(1, 100, 1), 10, "픽스처 단위", null),
                    new AnalysisCondition(AllowedRange.between(1, 100, 1), 10, "픽스처 단위", null),
                    new AnalysisCondition(
                            AllowedRange.ofValues(List.of(0, 1, 3)), 1, "픽스처 단위", null));

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

    /** 004가 실행을 지원한다고 답하는 fixture. 규칙 버전은 모두 {@code FIXTURE-RULES-1}이다. */
    public static StrategyExecutionSupportPort supporting(String... strategyIds) {
        Map<String, String> supported = new LinkedHashMap<>();
        for (String strategyId : strategyIds) {
            supported.put(strategyId, "FIXTURE-RULES-1");
        }
        Map<String, String> unmodifiable = Map.copyOf(supported);
        return () -> unmodifiable;
    }

    /** 조회가 실패하는 등록표. 503 경로(FR-028, research R9) 검증용이다. */
    public static ParameterRegistryPort unavailable() {
        return new FixtureRegistry(null, List.of(), Map.of()) {
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
        return new FixtureRegistry("FIXTURE-BROKEN", List.of(), Map.of()) {
            @Override
            public ValidationVerdict validate(
                    String strategyId, Map<String, Object> parameters, SearchContext context) {
                throw new IllegalStateException("registry down");
            }
        };
    }

    public static final class Builder {

        private final String version;
        private final List<StrategyRegistration> strategies = new ArrayList<>();
        private final Map<String, List<FixtureRule>> rules = new LinkedHashMap<>();

        private Builder(String version) {
            this.version = version;
        }

        public Builder strategy(String strategyId, ParameterAxis... axes) {
            strategies.add(
                    new StrategyRegistration(
                            strategyId,
                            strategyId + " 이름",
                            null,
                            List.of(axes),
                            true,
                            null,
                            DEFAULT_TARGET_WEIGHT_SOURCE));
            rules.computeIfAbsent(strategyId, key -> new ArrayList<>());
            return this;
        }

        /** 그 전략의 활성 여부. 지정하지 않으면 활성이다. */
        public Builder active(String strategyId, boolean active) {
            return replace(
                    strategyId,
                    before ->
                            new StrategyRegistration(
                                    before.strategyId(),
                                    before.name(),
                                    before.description(),
                                    before.axes(),
                                    active,
                                    before.warmup(),
                                    before.targetWeightSource()));
        }

        /** 그 전략의 워밍업 산출 규칙. 지정하지 않으면 워밍업이 없다(0이다). */
        public Builder warmup(String strategyId, List<Object> sum) {
            return replace(
                    strategyId,
                    before ->
                            new StrategyRegistration(
                                    before.strategyId(),
                                    before.name(),
                                    before.description(),
                                    before.axes(),
                                    before.active(),
                                    new WarmupRule(sum),
                                    before.targetWeightSource()));
        }

        /** 그 전략의 목표 비중 출처. 지정하지 않으면 {@code COMPUTED}다. */
        public Builder targetWeightSource(String strategyId, String targetWeightSource) {
            return replace(
                    strategyId,
                    before ->
                            new StrategyRegistration(
                                    before.strategyId(),
                                    before.name(),
                                    before.description(),
                                    before.axes(),
                                    before.active(),
                                    before.warmup(),
                                    targetWeightSource));
        }

        private Builder replace(String strategyId, UnaryOperator<StrategyRegistration> change) {
            for (int i = 0; i < strategies.size(); i++) {
                if (strategies.get(i).strategyId().equals(strategyId)) {
                    strategies.set(i, change.apply(strategies.get(i)));
                    return this;
                }
            }
            throw new IllegalArgumentException("fixture has no such strategy: " + strategyId);
        }

        /** 유효성 규칙. 조건이 참이면 위반이다 — 등록표가 판정을 소유한다는 계약을 fixture도 그대로 따른다. */
        public Builder rule(
                String strategyId,
                String reasonCode,
                BiPredicate<Map<String, Object>, SearchContext> violated) {
            rules.computeIfAbsent(strategyId, key -> new ArrayList<>())
                    .add(new FixtureRule(reasonCode, violated));
            return this;
        }

        public ParameterRegistryPort build() {
            return new FixtureRegistry(version, List.copyOf(strategies), Map.copyOf(rules));
        }
    }

    private record FixtureRule(
            String reasonCode, BiPredicate<Map<String, Object>, SearchContext> violated) {}

    private static class FixtureRegistry implements ParameterRegistryPort {

        private final String version;
        private final List<StrategyRegistration> strategies;
        private final Map<String, List<FixtureRule>> rules;

        private FixtureRegistry(
                String version,
                List<StrategyRegistration> strategies,
                Map<String, List<FixtureRule>> rules) {
            this.version = version;
            this.strategies = strategies;
            this.rules = rules;
        }

        @Override
        public String version() {
            return version;
        }

        @Override
        public boolean provisional() {
            return version != null && version.startsWith("PROVISIONAL-");
        }

        @Override
        public List<StrategyRegistration> activeStrategies() {
            return strategies.stream().filter(StrategyRegistration::active).toList();
        }

        /**
         * fixture의 규칙은 {@link BiPredicate}라 조건을 밖으로 내보낼 형태가 아니다. 규칙 목록을 읽는 쪽(003 조회 API)의 검증은 실제
         * 로더로 하고 여기서는 빈 목록을 돌려준다.
         */
        @Override
        public List<ValidationRule> rules(String strategyId) {
            return List.of();
        }

        @Override
        public AnalysisConditionRegistry analysisConditions() {
            return FIXTURE_CONDITIONS;
        }

        @Override
        public Optional<StrategyRegistration> find(String strategyId) {
            return strategies.stream()
                    .filter(strategy -> strategy.strategyId().equals(strategyId))
                    .findFirst();
        }

        @Override
        public ValidationVerdict validate(
                String strategyId, Map<String, Object> parameters, SearchContext context) {
            for (FixtureRule rule : rules.getOrDefault(strategyId, List.of())) {
                if (rule.violated().test(parameters, context)) {
                    return ValidationVerdict.violation(
                            rule.reasonCode(), rule.reasonCode() + " 위반입니다");
                }
            }
            return ValidationVerdict.ok();
        }

        /** fixture 등록표에는 기준 전략이 없다. 기준 전략 경로는 실제 등록표를 쓰는 {@code ParamSearchLaunchIT}가 본다. */
        @Override
        public Optional<String> baselineStrategyId() {
            return Optional.empty();
        }
    }
}
