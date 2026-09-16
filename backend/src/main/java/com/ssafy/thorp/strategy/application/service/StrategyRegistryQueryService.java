package com.ssafy.thorp.strategy.application.service;

import com.ssafy.thorp.strategy.application.port.AllowedRange;
import com.ssafy.thorp.strategy.application.port.AnalysisCondition;
import com.ssafy.thorp.strategy.application.port.AnalysisConditionRegistry;
import com.ssafy.thorp.strategy.application.port.ParameterAxis;
import com.ssafy.thorp.strategy.application.port.ParameterRegistryPort;
import com.ssafy.thorp.strategy.application.port.RegisteredChoice;
import com.ssafy.thorp.strategy.application.port.SearchContext;
import com.ssafy.thorp.strategy.application.port.StrategyRegistration;
import com.ssafy.thorp.strategy.application.port.ValidationRule;
import com.ssafy.thorp.strategy.application.port.ValidationVerdict;
import com.ssafy.thorp.strategy.application.port.WarmupRule;
import com.ssafy.thorp.strategy.application.result.StrategyRegistryResult;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * 전략 선택 화면이 읽는 등록표 조회(contracts/strategy-registry-api.md §1).
 *
 * <p><b>전략 지식을 여기에 두지 않는다.</b> 전략 목록·축 구성·후보값·허용 범위·규칙은 모두 등록표가 소유하고 이 서비스는 화면이 그릴 수 있는 모양으로 옮길
 * 뿐이다(FR-005). {@code strategyId}로 분기하는 코드가 생기면 그것이 등록표를 둔 뜻을 잃는 것이다.
 *
 * <p><b>조회 실패를 대체하지 않는다.</b> 포트가 {@link
 * com.ssafy.thorp.strategy.application.exception.RegistryUnavailableException}을 던지면 잡지 않고 전파해 503으로
 * 나간다. 캐시나 기본값으로 채워 응답하면 화면이 그 사실을 알 방법이 없다(FR-018).
 */
@Service
public class StrategyRegistryQueryService {

    private final ParameterRegistryPort registry;

    public StrategyRegistryQueryService(ParameterRegistryPort registry) {
        this.registry = registry;
    }

    /**
     * 활성 전략과 그 설정 항목을 화면이 쓸 모양으로 돌려준다.
     *
     * @param universeSize 사용자가 002에서 고른 섹터 수. 주면 유효성 제약을 미리 적용해 고를 수 없는 전략과 후보를 표시한다. <b>주지 않으면 제약을
     *     적용하지 않는다</b> — 섹터를 아직 고르지 않은 화면은 판정할 근거가 없고, 없는 근거로 막지 않는다(계약 §1)
     */
    public StrategyRegistryResult query(Integer universeSize) {
        SearchContext context = universeSize == null ? null : new SearchContext(universeSize);
        List<StrategyRegistryResult.StrategyView> strategies =
                registry.activeStrategies().stream()
                        .map(registration -> toStrategyView(registration, context))
                        .toList();
        return new StrategyRegistryResult(
                registry.version(),
                registry.provisional(),
                toConditions(registry.analysisConditions()),
                strategies);
    }

    private StrategyRegistryResult.StrategyView toStrategyView(
            StrategyRegistration registration, SearchContext context) {
        Map<String, Object> defaults = defaults(registration);
        List<ValidationRule> rules = registry.rules(registration.strategyId());
        ValidationVerdict verdict = verdict(registration.strategyId(), defaults, context);

        return new StrategyRegistryResult.StrategyView(
                registration.strategyId(),
                registration.name(),
                registration.description(),
                registration.targetWeightSource(),
                verdict.valid(),
                verdict.reasonDetail(),
                warmupTradingDays(registration, defaults),
                registration.axes().stream()
                        .map(axis -> toAxisView(registration, axis, defaults, rules, context))
                        .toList(),
                rules.stream().map(StrategyRegistryQueryService::toRuleView).toList(),
                toWarmupRuleView(registration.warmup()));
    }

    /**
     * 카드 자체의 선택 가능 여부. 기본 설정 전체를 <b>모든 규칙으로</b> 판정한다.
     *
     * <p>축 후보의 판정과 다른 판정이다 — 후보는 그 축을 참조하는 규칙만 본다({@link #choiceSelectable}).
     */
    private ValidationVerdict verdict(
            String strategyId, Map<String, Object> defaults, SearchContext context) {
        if (context == null) {
            return ValidationVerdict.ok();
        }
        return registry.validate(strategyId, defaults, context);
    }

    /** 워밍업 규칙이 없으면 0이다. 규칙이 없는 것과 0인 것을 등록표가 같게 보기로 했다(data-model §6). */
    private static int warmupTradingDays(
            StrategyRegistration registration, Map<String, Object> defaults) {
        WarmupRule warmup = registration.warmup();
        return warmup == null ? 0 : warmup.evaluate(defaults);
    }

    private static Map<String, Object> defaults(StrategyRegistration registration) {
        Map<String, Object> values = new LinkedHashMap<>();
        for (ParameterAxis axis : registration.axes()) {
            // 값에 null을 담지 않는다(포트 계약). 기본값이 비어 있으면 그 축을 조합에서 뺀다.
            if (axis.defaultValue() != null) {
                values.put(axis.name(), axis.defaultValue());
            }
        }
        return values;
    }

    private StrategyRegistryResult.AxisView toAxisView(
            StrategyRegistration registration,
            ParameterAxis axis,
            Map<String, Object> defaults,
            List<ValidationRule> rules,
            SearchContext context) {
        AllowedRange allowedRange = axis.allowedRange();
        if (allowedRange == null) {
            // 허용 범위가 없으면 화면이 목록을 그릴지 입력칸을 그릴지 정할 수 없다. 형태를 지어내지 않는다.
            throw new IllegalStateException(
                    "axis has no allowedRange: " + registration.strategyId() + "/" + axis.name());
        }
        boolean enumerated = allowedRange.isEnumerated();
        return new StrategyRegistryResult.AxisView(
                axis.name(),
                axis.label(),
                axis.description(),
                axis.unit(),
                enumerated
                        ? StrategyRegistryResult.InputType.CHOICE
                        : StrategyRegistryResult.InputType.NUMBER,
                enumerated
                        ? toChoices(registration, axis, allowedRange, defaults, rules, context)
                        : null,
                enumerated
                        ? null
                        : new StrategyRegistryResult.RangeView(
                                allowedRange.min(), allowedRange.max(), allowedRange.step()),
                axis.defaultValue());
    }

    private List<StrategyRegistryResult.ChoiceView> toChoices(
            StrategyRegistration registration,
            ParameterAxis axis,
            AllowedRange allowedRange,
            Map<String, Object> defaults,
            List<ValidationRule> rules,
            SearchContext context) {
        List<ValidationRule> axisRules =
                rules.stream().filter(rule -> references(rule, axis.name())).toList();
        return allowedRange.choices().stream()
                .map(
                        choice ->
                                new StrategyRegistryResult.ChoiceView(
                                        choice.value(),
                                        label(choice, axis.unit()),
                                        choiceSelectable(
                                                registration.strategyId(),
                                                axis.name(),
                                                choice.value(),
                                                defaults,
                                                axisRules,
                                                context)))
                .toList();
    }

    /**
     * 후보 하나를 고를 수 있는가. 기본 설정에서 <b>그 축의 값 하나만</b> 후보로 바꿔 판정한다.
     *
     * <p><b>그 축을 참조하는 규칙이 없으면 판정하지 않고 고를 수 있다고 본다.</b> 그러지 않으면 다른 축 때문에 생긴 위반이 이 축의 후보를 잠근다 — 선정 수가
     * 후보 섹터보다 많다는 이유로 관측 기간 후보까지 전부 잠기면 사용자는 틀린 안내를 받는다(계약 §1).
     *
     * <p>한 전략이 서로 다른 축을 참조하는 규칙을 둘 이상 가지면 이 판정도 함께 섞인다 — 포트가 규칙 하나만 평가하는 수단을 주지 않기 때문이다. 지금 등록표는
     * 전략마다 규칙이 하나뿐이라 겪지 않는다. 규칙이 늘면 포트에 규칙 단위 판정을 더한다.
     */
    private boolean choiceSelectable(
            String strategyId,
            String axisName,
            Object value,
            Map<String, Object> defaults,
            List<ValidationRule> axisRules,
            SearchContext context) {
        if (context == null || axisRules.isEmpty()) {
            return true;
        }
        Map<String, Object> candidate = new LinkedHashMap<>(defaults);
        candidate.put(axisName, value);
        return registry.validate(strategyId, candidate, context).valid();
    }

    private static boolean references(ValidationRule rule, String axisName) {
        return axisName.equals(rule.left().axis()) || axisName.equals(rule.right().axis());
    }

    /**
     * 후보의 표시 문구. <b>등록표가 적은 문구를 그대로 쓴다.</b>
     *
     * <p>적지 않은 후보는 값과 축의 단위를 붙인다({@code 21} + {@code 거래일}). 숫자가 아닌 후보는 로더가 문구를 요구하므로 문구 없이 여기까지 오지
     * 않는다 — 무엇이 문구를 가져야 하는지는 등록표가 정하고, 이 메서드는 값이나 축 이름을 해석하지 않는다(FR-005).
     */
    private static String label(RegisteredChoice choice, String unit) {
        if (choice.label() != null) {
            return choice.label();
        }
        return unit == null ? String.valueOf(choice.value()) : choice.value() + unit;
    }

    private static StrategyRegistryResult.RuleView toRuleView(ValidationRule rule) {
        return new StrategyRegistryResult.RuleView(
                rule.code(),
                rule.detail(),
                toOperandView(rule.left()),
                rule.operator(),
                toOperandView(rule.right()));
    }

    private static StrategyRegistryResult.OperandView toOperandView(
            ValidationRule.Operand operand) {
        return new StrategyRegistryResult.OperandView(
                operand.axis(), operand.context(), operand.value(), operand.multiplyByContext());
    }

    private static StrategyRegistryResult.WarmupRuleView toWarmupRuleView(WarmupRule warmup) {
        return warmup == null ? null : new StrategyRegistryResult.WarmupRuleView(warmup.sum());
    }

    private static StrategyRegistryResult.AnalysisConditionsView toConditions(
            AnalysisConditionRegistry conditions) {
        return new StrategyRegistryResult.AnalysisConditionsView(
                toCondition(conditions.initialAmount()),
                toCondition(conditions.targetVolatility()),
                toCondition(conditions.minEvaluationYears()));
    }

    private static StrategyRegistryResult.ConditionView toCondition(AnalysisCondition condition) {
        AllowedRange range = condition.range();
        return new StrategyRegistryResult.ConditionView(
                range.min(),
                range.max(),
                range.step(),
                range.isEnumerated() ? range.choices() : null,
                condition.defaultValue(),
                condition.unit(),
                condition.usedBy());
    }
}
