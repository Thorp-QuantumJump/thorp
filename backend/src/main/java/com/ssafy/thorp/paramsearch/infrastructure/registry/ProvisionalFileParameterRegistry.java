package com.ssafy.thorp.paramsearch.infrastructure.registry;

import com.ssafy.thorp.paramsearch.application.port.AllowedRange;
import com.ssafy.thorp.paramsearch.application.port.ParameterAxis;
import com.ssafy.thorp.paramsearch.application.port.ParameterRegistryPort;
import com.ssafy.thorp.paramsearch.application.port.SearchContext;
import com.ssafy.thorp.paramsearch.application.port.StrategyRegistration;
import com.ssafy.thorp.paramsearch.application.port.ValidationVerdict;
import com.ssafy.thorp.paramsearch.domain.vo.ParameterCombination;
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
 * spec 003이 완성될 때까지 쓰는 임시 등록표 구현(research R1 개정).
 *
 * <p><b>이 값은 팀 확정값이 아니다(PD-02 미확정).</b> {@link #version()}이 {@code PROVISIONAL-}로 시작하는 것으로 그 사실을
 * API 응답과 화면에 계속 표시한다. 003이 완성되면 이 클래스를 003 구현체로 교체한다.
 *
 * <p><b>전략·축·후보값을 Java 상수로 두지 않는다.</b> 전부 자원 파일 {@code
 * registry/parameter-registry-provisional.yml}에서 읽는다 — 코드 상수로 두면 그것이 곧 007의 하드코딩이다(FR-025). 이 로더는
 * 파일 구조만 읽고 전략별 해석을 하지 않으므로 파일에 전략을 더해도 코드는 바뀌지 않는다.
 *
 * <p>파일은 기동 시 한 번 읽어 불변으로 들고 있는다. 같은 {@link #version()} 안에서 축 순서와 후보값 인덱스가 흔들리면 저장된 그리드 좌표가 뜻을 잃기
 * 때문이다(FR-029).
 */
@Component
public class ProvisionalFileParameterRegistry implements ParameterRegistryPort {

    static final String RESOURCE_PATH = "registry/parameter-registry-provisional.yml";
    private static final String PROVISIONAL_PREFIX = "PROVISIONAL-";

    private final String version;
    private final List<StrategyRegistration> activeStrategies;
    private final Map<String, StrategyRegistration> byStrategyId;
    private final Map<String, List<RegistryRule>> rulesByStrategyId;
    private final String baselineStrategyId;

    public ProvisionalFileParameterRegistry() {
        this(new ClassPathResource(RESOURCE_PATH));
    }

    ProvisionalFileParameterRegistry(Resource resource) {
        Map<String, Object> document = read(resource);
        this.version = requireProvisionalVersion(string(document, "version"));
        this.baselineStrategyId = string(document, "baselineStrategyId");

        List<StrategyRegistration> registrations = new ArrayList<>();
        Map<String, StrategyRegistration> index = new LinkedHashMap<>();
        Map<String, List<RegistryRule>> rules = new LinkedHashMap<>();
        for (Map<String, Object> strategy : list(document, "strategies")) {
            StrategyRegistration registration = toRegistration(strategy);
            if (index.put(registration.strategyId(), registration) != null) {
                throw new IllegalStateException(
                        "duplicate strategy in registry: " + registration.strategyId());
            }
            registrations.add(registration);
            rules.put(registration.strategyId(), toRules(strategy));
        }
        if (baselineStrategyId != null && !index.containsKey(baselineStrategyId)) {
            throw new IllegalStateException(
                    "baselineStrategyId is not registered: " + baselineStrategyId);
        }
        this.activeStrategies = List.copyOf(registrations);
        this.byStrategyId = Map.copyOf(index);
        this.rulesByStrategyId = Map.copyOf(rules);
    }

    @Override
    public String version() {
        return version;
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
    public ValidationVerdict validate(
            String strategyId, ParameterCombination combination, SearchContext context) {
        List<RegistryRule> rules = rulesByStrategyId.get(strategyId);
        if (rules == null) {
            throw new IllegalArgumentException("unknown strategy: " + strategyId);
        }
        for (RegistryRule rule : rules) {
            if (rule.isViolatedBy(combination, context)) {
                return ValidationVerdict.violation(rule.code(), rule.detail());
            }
        }
        return ValidationVerdict.ok();
    }

    @Override
    public Optional<String> baselineStrategyId() {
        return Optional.ofNullable(baselineStrategyId);
    }

    private static StrategyRegistration toRegistration(Map<String, Object> strategy) {
        List<ParameterAxis> axes = new ArrayList<>();
        for (Map<String, Object> axis : list(strategy, "axes")) {
            axes.add(
                    new ParameterAxis(
                            string(axis, "name"),
                            string(axis, "label"),
                            string(axis, "unit"),
                            values(axis),
                            axis.get("defaultValue"),
                            toAllowedRange(map(axis, "allowedRange"))));
        }
        return new StrategyRegistration(
                string(strategy, "id"),
                string(strategy, "name"),
                string(strategy, "description"),
                axes);
    }

    private static List<Object> values(Map<String, Object> axis) {
        Object searchValues = axis.get("searchValues");
        if (!(searchValues instanceof List<?> raw)) {
            throw new IllegalStateException("axis needs searchValues: " + axis.get("name"));
        }
        return List.copyOf(raw);
    }

    private static AllowedRange toAllowedRange(Map<String, Object> range) {
        if (range == null) {
            return null;
        }
        Object values = range.get("values");
        return new AllowedRange(
                range.get("min"),
                range.get("max"),
                range.get("step"),
                values instanceof List<?> list ? List.copyOf(list) : null);
    }

    private static List<RegistryRule> toRules(Map<String, Object> strategy) {
        List<RegistryRule> rules = new ArrayList<>();
        for (Map<String, Object> rule : list(strategy, "validationRules")) {
            rules.add(
                    new RegistryRule(
                            string(rule, "code"),
                            string(rule, "detail"),
                            toOperand(map(rule, "left")),
                            RegistryRule.Operator.from(string(rule, "operator")),
                            toOperand(map(rule, "right"))));
        }
        return List.copyOf(rules);
    }

    private static RegistryRule.Operand toOperand(Map<String, Object> operand) {
        if (operand == null) {
            throw new IllegalStateException("validation rule needs both operands");
        }
        if (operand.get("axis") != null) {
            return RegistryRule.Operand.ofAxis(
                    String.valueOf(operand.get("axis")), string(operand, "multiplyByContext"));
        }
        if (operand.get("context") != null) {
            return RegistryRule.Operand.ofContext(String.valueOf(operand.get("context")));
        }
        if (operand.get("value") != null) {
            return RegistryRule.Operand.ofLiteral(operand.get("value"));
        }
        throw new IllegalStateException("operand must set one of axis, context, value");
    }

    private static String requireProvisionalVersion(String version) {
        if (version == null || !version.startsWith(PROVISIONAL_PREFIX)) {
            // 임시 값임을 화면과 응답이 계속 표시할 수 있어야 한다. 접두가 빠지면 그 표시가 사라진다.
            throw new IllegalStateException(
                    "provisional registry version must start with " + PROVISIONAL_PREFIX);
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
            throw new IllegalStateException("cannot read provisional registry: " + resource, e);
        }
    }

    private static String string(Map<String, Object> source, String key) {
        Object value = source.get(key);
        return value == null ? null : String.valueOf(value);
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
