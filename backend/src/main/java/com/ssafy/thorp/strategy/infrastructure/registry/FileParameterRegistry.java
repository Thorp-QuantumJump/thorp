package com.ssafy.thorp.strategy.infrastructure.registry;

import com.ssafy.thorp.strategy.application.port.AllowedRange;
import com.ssafy.thorp.strategy.application.port.AnalysisCondition;
import com.ssafy.thorp.strategy.application.port.AnalysisConditionRegistry;
import com.ssafy.thorp.strategy.application.port.AxisCondition;
import com.ssafy.thorp.strategy.application.port.ParameterAxis;
import com.ssafy.thorp.strategy.application.port.ParameterRegistryPort;
import com.ssafy.thorp.strategy.application.port.RegisteredChoice;
import com.ssafy.thorp.strategy.application.port.SearchContext;
import com.ssafy.thorp.strategy.application.port.StrategyRegistration;
import com.ssafy.thorp.strategy.application.port.ValidationRule;
import com.ssafy.thorp.strategy.application.port.ValidationVerdict;
import com.ssafy.thorp.strategy.application.port.WarmupRule;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

/**
 * 003이 소유한 전략·파라미터 등록표를 자원 파일에서 읽는 구현(내부 계약 §1).
 *
 * <p><b>전략·축·후보값을 Java 상수로 두지 않는다.</b> 전부 자원 파일 {@code registry/parameter-registry.yml}에서 읽는다 — 코드
 * 상수로 두면 그것이 곧 하드코딩이다(007 FR-025). 이 로더는 <b>파일 구조만</b> 읽고 전략별 해석을 하지 않으므로 파일에 전략을 더해도 코드는 바뀌지 않는다.
 *
 * <p>파일은 기동 시 한 번 읽어 불변으로 들고 있는다. 같은 {@link #version()} 안에서 축 순서와 후보값 인덱스가 흔들리면 저장된 그리드 좌표가 뜻을 잃기
 * 때문이다(007 FR-029, 내부 계약 §2.1).
 *
 * <p><b>잘못 적힌 등록표로는 기동하지 않는다.</b> 등록표가 어긋난 채 서비스가 뜨면 사용자가 그 사실을 알 방법이 없다 — 화면은 허용 범위 밖 기본값을 보여 주고,
 * 자동 탐색은 사용자가 고를 수 없는 값을 훑는다(data-model §3).
 */
@Component
public class FileParameterRegistry implements ParameterRegistryPort {

    static final String RESOURCE_PATH = "registry/parameter-registry.yml";
    private static final String PROVISIONAL_PREFIX = "PROVISIONAL-";

    private final String version;
    private final List<StrategyRegistration> activeStrategies;
    private final Map<String, StrategyRegistration> byStrategyId;
    private final Map<String, List<RegistryRule>> rulesByStrategyId;
    private final String baselineStrategyId;
    private final AnalysisConditionRegistry analysisConditions;

    public FileParameterRegistry() {
        this(new ClassPathResource(RESOURCE_PATH));
    }

    FileParameterRegistry(Resource resource) {
        Map<String, Object> document = read(resource);
        this.version = requireVersion(string(document, "version"));
        this.baselineStrategyId = string(document, "baselineStrategyId");

        List<StrategyRegistration> active = new ArrayList<>();
        Map<String, StrategyRegistration> index = new LinkedHashMap<>();
        Map<String, List<RegistryRule>> rules = new LinkedHashMap<>();
        for (Map<String, Object> strategy : list(document, "strategies")) {
            StrategyRegistration registration = toRegistration(strategy);
            if (index.put(registration.strategyId(), registration) != null) {
                throw new IllegalStateException(
                        "duplicate strategy in registry: " + registration.strategyId());
            }
            if (registration.active()) {
                active.add(registration);
            }
            rules.put(registration.strategyId(), toRules(strategy));
        }
        requireRegisteredBaseline(index);
        this.activeStrategies = List.copyOf(active);
        this.byStrategyId = Map.copyOf(index);
        this.rulesByStrategyId = Map.copyOf(rules);
        this.analysisConditions = toAnalysisConditions(map(document, "analysisConditions"));
    }

    @Override
    public String version() {
        return version;
    }

    @Override
    public boolean provisional() {
        return version.startsWith(PROVISIONAL_PREFIX);
    }

    @Override
    public List<StrategyRegistration> activeStrategies() {
        return activeStrategies;
    }

    @Override
    public Optional<StrategyRegistration> find(String strategyId) {
        return Optional.ofNullable(byStrategyId.get(strategyId));
    }

    @Override
    public List<ValidationRule> rules(String strategyId) {
        return rulesByStrategyId.getOrDefault(strategyId, List.of()).stream()
                .map(RegistryRule::rule)
                .toList();
    }

    @Override
    public AnalysisConditionRegistry analysisConditions() {
        return analysisConditions;
    }

    @Override
    public ValidationVerdict validate(
            String strategyId, Map<String, Object> parameters, SearchContext context) {
        List<RegistryRule> rules = rulesByStrategyId.get(strategyId);
        if (rules == null) {
            throw new IllegalArgumentException("unknown strategy: " + strategyId);
        }
        for (RegistryRule rule : rules) {
            if (rule.isViolatedBy(parameters, context)) {
                return ValidationVerdict.violation(rule.code(), rule.detail());
            }
        }
        return ValidationVerdict.ok();
    }

    @Override
    public Optional<String> baselineStrategyId() {
        return Optional.ofNullable(baselineStrategyId);
    }

    private void requireRegisteredBaseline(Map<String, StrategyRegistration> index) {
        if (baselineStrategyId == null) {
            return;
        }
        StrategyRegistration baseline = index.get(baselineStrategyId);
        if (baseline == null) {
            throw new IllegalStateException(
                    "baselineStrategyId is not registered: " + baselineStrategyId);
        }
        if (!baseline.active()) {
            // 손실 제약의 기준이 비활성이면 새 탐색이 고를 수 없는 전략을 기준으로 삼는 셈이다(data-model §1).
            throw new IllegalStateException(
                    "baselineStrategyId is not active: " + baselineStrategyId);
        }
    }

    private static StrategyRegistration toRegistration(Map<String, Object> strategy) {
        String strategyId = string(strategy, "id");
        List<ParameterAxis> axes = new ArrayList<>();
        for (Map<String, Object> axis : list(strategy, "axes")) {
            axes.add(toAxis(strategyId, axis));
        }
        StrategyRegistration registration;
        try {
            registration =
                    new StrategyRegistration(
                            strategyId,
                            string(strategy, "name"),
                            string(strategy, "description"),
                            axes,
                            bool(strategy, "active"),
                            toWarmup(strategyId, map(strategy, "warmup")),
                            string(strategy, "targetWeightSource"));
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(
                    "invalid strategy registration: " + strategyId + " - " + e.getMessage(), e);
        }
        requireConditionValues(registration);
        return registration;
    }

    private static ParameterAxis toAxis(String strategyId, Map<String, Object> axis) {
        String axisName = string(axis, "name");
        String where = strategyId + "/" + axisName;
        AllowedRange allowedRange = toAllowedRange(where, map(axis, "allowedRange"));
        if (allowedRange == null) {
            // 허용 범위가 없으면 화면이 목록을 그릴지 입력칸을 그릴지 정할 수 없고, 입력값을 판정할 근거도 없다.
            // 조회할 때 드러나면 이미 늦다 — 그때는 사용자가 빈 화면을 보고 있다(data-model §3).
            throw new IllegalStateException("axis needs an allowedRange: " + where);
        }
        List<Object> searchValues = searchValues(axis);
        Object defaultValue = axis.get("defaultValue");
        requireWithinAllowedRange(where, allowedRange, searchValues, defaultValue);

        return new ParameterAxis(
                axisName,
                string(axis, "label"),
                string(axis, "description"),
                string(axis, "unit"),
                searchValues,
                defaultValue,
                allowedRange,
                toCondition(where, map(axis, "appliesWhen")),
                string(axis, "termId"));
    }

    /**
     * 축의 적용 조건(spec FR-003). 결정 축의 값이 그 축의 허용 범위 안인지는 전략의 축이 모두 모인 뒤 {@link
     * #requireConditionValues}가 본다.
     */
    private static AxisCondition toCondition(String where, Map<String, Object> condition) {
        if (condition == null) {
            return null;
        }
        try {
            return new AxisCondition(string(condition, "axis"), condition.get("value"));
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new IllegalStateException(
                    "invalid appliesWhen: " + where + " - " + e.getMessage(), e);
        }
    }

    /** 조건이 가리키는 값이 결정 축의 후보에 없으면 그 축은 어떤 설정에서도 열리지 않는다. 등록표가 잘못 적힌 것이라 기동에서 막는다(data-model §3). */
    private static void requireConditionValues(StrategyRegistration registration) {
        for (ParameterAxis axis : registration.axes()) {
            AxisCondition condition = axis.appliesWhen();
            if (condition == null) {
                continue;
            }
            ParameterAxis decider = registration.axis(condition.axis()).orElseThrow();
            if (decider.allowedRange() == null
                    || !decider.allowedRange().contains(condition.value())) {
                throw new IllegalStateException(
                        "appliesWhen.value is outside the deciding axis allowedRange: "
                                + registration.strategyId()
                                + "/"
                                + axis.name()
                                + " -> "
                                + condition);
            }
        }
    }

    private static void requireWithinAllowedRange(
            String where,
            AllowedRange allowedRange,
            List<Object> searchValues,
            Object defaultValue) {
        if (allowedRange.isEnumerated()) {
            // 자동 탐색이 사용자가 고를 수 없는 값을 훑으면 그 결과를 상세 설정에서 재현할 수 없다(내부 계약 §2.5).
            List<Object> allowed = allowedRange.values();
            List<Object> outside =
                    searchValues.stream().filter(value -> !allowed.contains(value)).toList();
            if (!outside.isEmpty()) {
                throw new IllegalStateException(
                        "searchValues must be a subset of allowedRange.values: "
                                + where
                                + " -> "
                                + outside);
            }
        }
        if (!allowedRange.contains(defaultValue)) {
            // 화면이 열리자마자 허용 범위 밖 값을 보여 주게 된다(data-model §3).
            throw new IllegalStateException(
                    "defaultValue is outside allowedRange: " + where + " -> " + defaultValue);
        }
    }

    private static List<Object> searchValues(Map<String, Object> axis) {
        Object searchValues = axis.get("searchValues");
        if (!(searchValues instanceof List<?> raw)) {
            throw new IllegalStateException("axis needs searchValues: " + axis.get("name"));
        }
        return List.copyOf(raw);
    }

    private static AllowedRange toAllowedRange(String where, Map<String, Object> range) {
        if (range == null) {
            return null;
        }
        List<RegisteredChoice> choices = toChoices(where, range);
        try {
            return new AllowedRange(range.get("min"), range.get("max"), range.get("step"), choices);
        } catch (IllegalArgumentException e) {
            // 어느 축인지 붙여 다시 던진다 — 기동 실패 로그에 축 이름이 없으면 등록표를 뒤져야 한다.
            throw new IllegalStateException(
                    "invalid allowedRange: " + where + " - " + e.getMessage(), e);
        }
    }

    /**
     * 목록형 후보를 읽는다. 등록표는 둘 중 하나로 적는다 — 값만 적는 {@code values}, 값과 표시 문구를 함께 적는 {@code choices}.
     *
     * <p>둘을 함께 적으면 어느 쪽이 후보 목록인지 정할 수 없어 기동을 막는다.
     */
    private static List<RegisteredChoice> toChoices(String where, Map<String, Object> range) {
        Object values = range.get("values");
        Object labelled = range.get("choices");
        if (values != null && labelled != null) {
            throw new IllegalStateException(
                    "allowedRange must not fill both values and choices: " + where);
        }
        if (values instanceof List<?> raw) {
            return raw.stream().map(value -> readableWithoutLabel(where, value)).toList();
        }
        if (labelled instanceof List<?> raw) {
            return raw.stream().map(entry -> toChoice(where, entry)).toList();
        }
        return null;
    }

    /**
     * 표시 문구 없이 적을 수 있는 후보는 숫자뿐이다 — 값과 축의 단위를 붙이면 그대로 읽힌다.
     *
     * <p><b>{@code TOP_N}이나 {@code {mode: CALENDAR}}가 무슨 뜻인지는 등록표만 안다.</b> 문구 없이 두면 화면이 {@code
     * TOP_N}을 그대로 보이거나 무슨 말로 보일지 코드에 적게 되고, 후자가 FR-005가 금지한 "화면 코드에 따로 적어 두는 안내"다.
     */
    private static RegisteredChoice readableWithoutLabel(String where, Object value) {
        if (!(value instanceof Number)) {
            throw new IllegalStateException(
                    "non-numeric choice needs a label - use `choices:` instead of `values:`: "
                            + where
                            + " -> "
                            + value);
        }
        return RegisteredChoice.of(value);
    }

    private static RegisteredChoice toChoice(String where, Object entry) {
        if (!(entry instanceof Map<?, ?> choice)) {
            throw new IllegalStateException(
                    "choices entry must be a mapping of value and label: " + where);
        }
        Object value = choice.get("value");
        if (value == null) {
            throw new IllegalStateException("choices entry needs a value: " + where);
        }
        Object label = choice.get("label");
        if (label == null) {
            // 문구를 적으라고 만든 자리다. 비워 두면 values 로 적은 것과 같아진다.
            throw new IllegalStateException(
                    "choices entry needs a label: " + where + " -> " + value);
        }
        return new RegisteredChoice(value, String.valueOf(label));
    }

    private static WarmupRule toWarmup(String strategyId, Map<String, Object> warmup) {
        if (warmup == null) {
            return null;
        }
        Object sum = warmup.get("sum");
        if (!(sum instanceof List<?> raw)) {
            throw new IllegalStateException("warmup needs a sum list: " + strategyId);
        }
        return new WarmupRule(List.copyOf(raw));
    }

    private static AnalysisConditionRegistry toAnalysisConditions(Map<String, Object> conditions) {
        if (conditions == null) {
            // 시작 금액·최소 평가 기간 없이는 화면이 '기간·금액·조건'을 그릴 수 없다(data-model §7).
            throw new IllegalStateException("registry needs analysisConditions");
        }
        return new AnalysisConditionRegistry(
                toAnalysisCondition(conditions, "initialAmount"),
                toAnalysisCondition(conditions, "targetVolatility"),
                toAnalysisCondition(conditions, "minEvaluationYears"));
    }

    private static AnalysisCondition toAnalysisCondition(
            Map<String, Object> conditions, String key) {
        Map<String, Object> condition = map(conditions, key);
        if (condition == null) {
            throw new IllegalStateException("analysisConditions needs " + key);
        }
        String where = "analysisConditions/" + key;
        AllowedRange range = toAllowedRange(where, condition);
        if (range == null) {
            throw new IllegalStateException("analysisConditions needs a range: " + key);
        }
        Object defaultValue = condition.get("default");
        if (!range.contains(defaultValue)) {
            throw new IllegalStateException(
                    "default is outside its range: " + where + " -> " + defaultValue);
        }
        return new AnalysisCondition(
                range, defaultValue, string(condition, "unit"), string(condition, "usedBy"));
    }

    private static List<RegistryRule> toRules(Map<String, Object> strategy) {
        List<RegistryRule> rules = new ArrayList<>();
        for (Map<String, Object> rule : list(strategy, "validationRules")) {
            rules.add(
                    new RegistryRule(
                            new ValidationRule(
                                    string(rule, "code"),
                                    string(rule, "detail"),
                                    toOperand(map(rule, "left")),
                                    ValidationRule.Operator.from(string(rule, "operator")),
                                    toOperand(map(rule, "right")))));
        }
        return List.copyOf(rules);
    }

    private static ValidationRule.Operand toOperand(Map<String, Object> operand) {
        if (operand == null) {
            throw new IllegalStateException("validation rule needs both operands");
        }
        if (operand.get("axis") != null) {
            String axis = String.valueOf(operand.get("axis"));
            String byAxis = string(operand, "multiplyByAxis");
            String byContext = string(operand, "multiplyByContext");
            Map<String, Object> byCeil = map(operand, "multiplyByCeil");
            int multiplierCount =
                    (byAxis == null ? 0 : 1)
                            + (byContext == null ? 0 : 1)
                            + (byCeil == null ? 0 : 1);
            if (multiplierCount > 1) {
                throw new IllegalStateException("operand cannot set more than one multiplier");
            }
            if (byCeil != null) {
                return ValidationRule.Operand.ofAxisTimesCeilingFactor(
                        axis,
                        string(byCeil, "context"),
                        string(byCeil, "axis"),
                        byCeil.get("divisor"));
            }
            return byAxis == null
                    ? ValidationRule.Operand.ofAxis(axis, byContext)
                    : ValidationRule.Operand.ofAxisTimesAxis(axis, byAxis);
        }
        if (operand.get("context") != null) {
            return ValidationRule.Operand.ofContext(String.valueOf(operand.get("context")));
        }
        if (operand.get("value") != null) {
            return ValidationRule.Operand.ofValue(operand.get("value"));
        }
        throw new IllegalStateException("operand must set one of axis, context, value");
    }

    private static String requireVersion(String version) {
        if (version == null || version.isBlank()) {
            // 접수 시점에 묶음이 고정할 값이다. 비어 있으면 무엇으로 실행했는지 기록에 남지 않는다(007 FR-028).
            throw new IllegalStateException("registry version must not be blank");
        }
        return version;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> read(Resource resource) {
        try (InputStream stream = resource.getInputStream()) {
            Object loaded = new Yaml().load(stream);
            if (!(loaded instanceof Map<?, ?>)) {
                throw new IllegalStateException("registry file is not a mapping: " + resource);
            }
            return (Map<String, Object>) loaded;
        } catch (IOException e) {
            throw new IllegalStateException("cannot read registry: " + resource, e);
        }
    }

    private static String string(Map<String, Object> source, String key) {
        Object value = source.get(key);
        return value == null ? null : String.valueOf(value);
    }

    /** 적지 않으면 활성이다 — 전략마다 {@code active: true}를 되풀이해 적게 하지 않는다(data-model §2). */
    private static boolean bool(Map<String, Object> source, String key) {
        Object value = source.get(key);
        if (value == null) {
            return true;
        }
        if (value instanceof Boolean flag) {
            return flag;
        }
        throw new IllegalStateException("registry field must be a boolean: " + key);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> list(Map<String, Object> source, String key) {
        Object value = source.get(key);
        if (value == null) {
            return List.of();
        }
        if (!(value instanceof List<?> raw)) {
            throw new IllegalStateException("registry field must be a list: " + key);
        }
        return (List<Map<String, Object>>) raw;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Map<String, Object> source, String key) {
        Object value = source.get(key);
        if (value == null) {
            return null;
        }
        if (!(value instanceof Map<?, ?>)) {
            throw new IllegalStateException("registry field must be a mapping: " + key);
        }
        return (Map<String, Object>) value;
    }
}
