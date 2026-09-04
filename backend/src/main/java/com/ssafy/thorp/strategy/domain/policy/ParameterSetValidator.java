package com.ssafy.thorp.strategy.domain.policy;

import com.ssafy.thorp.strategy.domain.model.ParameterConstraint;
import com.ssafy.thorp.strategy.domain.model.ParameterDefinition;
import com.ssafy.thorp.strategy.domain.model.ParameterViolation;
import com.ssafy.thorp.strategy.domain.model.StrategyCatalog;
import com.ssafy.thorp.strategy.domain.model.StrategyDefinition;
import com.ssafy.thorp.strategy.domain.model.ValidationOutcome;
import com.ssafy.thorp.strategy.domain.vo.EnumOption;
import com.ssafy.thorp.strategy.domain.vo.ExecutionScope;
import com.ssafy.thorp.strategy.domain.vo.NumericRange;
import com.ssafy.thorp.strategy.domain.vo.ParameterValue;
import com.ssafy.thorp.strategy.domain.vo.ResolvedParameters;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 파라미터 값 집합을 전략 정의에 대해 판정한다(FR-011 ~ FR-014, FR-021).
 *
 * <p>판정 순서는 계약이다. 소비 지점이 여럿이고(BE·FE) 테스트 벡터가 위반의 <b>배열 순서까지</b> 비교하므로, 여기서 순서를 바꾸면 FE 미러도 함께 바꿔야
 * 한다.
 *
 * <pre>
 * S0.  전략 조회            실패 → UNKNOWN_STRATEGY 하나만 반환하고 종료
 * S0b. 실행 목적 지원 여부   실패 → EXECUTION_SCOPE_NOT_SUPPORTED 하나만 반환하고 종료
 * S1.  미정의 파라미터      입력 키 - 적용 대상 키 → UNKNOWN_PARAMETER
 * S2.  필수 누락            REQUIRED_PARAMETER_MISSING
 * S3.  타입 판정            TYPE_MISMATCH (실패한 값은 S4·S6에서 제외)
 * S4.  범위·간격·허용 값    OUT_OF_RANGE / STEP_MISMATCH / NOT_IN_ENUM
 * S5.  기본값 주입          ResolvedParameters
 * S6.  상호 제약            CONSTRAINT_VIOLATED
 * S7.  이력 요구량 산출     위반이 하나도 없을 때만
 * </pre>
 *
 * <p>S1~S6은 중단 없이 모두 수행해 위반을 누적한다(FR-012). 위반 순서는 단계 순, 같은 단계 안에서는 정의의 파라미터 선언 순서다.
 *
 * <p>타입이 깨진 값은 S4·S6에서 제외한다 — 값이 없는데 범위 위반을 보고하면 거짓 정보를 준다. 반대로 <b>범위를 벗어난 값은 S6 판정에 그대로 쓴다</b>.
 * 타입은 확정돼 있어 판정이 가능하고, 그래야 사용자가 범위 위반과 제약 위반을 한 번에 본다.
 *
 * <p>입력 값의 런타임 타입은 {@code Number} · {@code Boolean} · {@code String}만 해석하며 그 외는 모두 타입 불일치로 본다. JSON
 * 파싱은 presentation 계층이 하고 도메인은 스칼라만 받는다.
 */
public final class ParameterSetValidator {

    private ParameterSetValidator() {}

    /** 카탈로그에서 전략을 찾는 단계(S0)까지 포함한 판정. */
    public static ValidationOutcome validate(
            StrategyCatalog catalog,
            String strategyId,
            ExecutionScope executionScope,
            Map<String, Object> values) {
        return catalog.find(strategyId)
                .map(definition -> validate(definition, executionScope, values))
                .orElseGet(
                        () ->
                                ValidationOutcome.rejected(
                                        ParameterViolation.unknownStrategy(strategyId)));
    }

    /** 전략이 이미 특정된 상태의 판정(S0b~S7). */
    public static ValidationOutcome validate(
            StrategyDefinition definition,
            ExecutionScope executionScope,
            Map<String, Object> values) {
        if (!definition.supports(executionScope)) {
            return ValidationOutcome.rejected(
                    ParameterViolation.executionScopeNotSupported(definition, executionScope));
        }

        // 실행 목적에 해당하지 않는 파라미터는 이 요청에서 존재하지 않는 것으로 다룬다(FR-021).
        List<ParameterDefinition> applicable = definition.parametersFor(executionScope);
        Map<String, ParameterDefinition> byName = new LinkedHashMap<>();
        applicable.forEach(parameter -> byName.put(parameter.name(), parameter));

        List<ParameterViolation> unknown = new ArrayList<>();
        List<ParameterViolation> missing = new ArrayList<>();
        List<ParameterViolation> typeErrors = new ArrayList<>();
        List<ParameterViolation> rangeErrors = new ArrayList<>();

        // S1
        values.keySet().stream()
                .filter(name -> !byName.containsKey(name))
                .forEach(
                        name ->
                                unknown.add(
                                        ParameterViolation.unknownParameter(name, executionScope)));

        // S2~S4
        Map<String, ParameterValue> typed = new LinkedHashMap<>();
        for (ParameterDefinition parameter : applicable) {
            if (!values.containsKey(parameter.name())) {
                if (parameter.required()) {
                    missing.add(ParameterViolation.requiredMissing(parameter));
                }
                continue;
            }
            Object raw = values.get(parameter.name());
            Optional<ParameterValue> converted = convert(parameter, raw);
            if (converted.isEmpty()) {
                typeErrors.add(ParameterViolation.typeMismatch(parameter, raw));
                continue;
            }
            typed.put(parameter.name(), converted.get());
            checkValueRange(parameter, converted.get()).ifPresent(rangeErrors::add);
        }

        List<ParameterViolation> violations = new ArrayList<>();
        violations.addAll(unknown);
        violations.addAll(missing);
        violations.addAll(typeErrors);
        violations.addAll(rangeErrors);

        // S5 — 생략된 선택 파라미터에 기본값을 채운다. 타입이 깨진 값은 확정되지 않은 것으로 둔다.
        Map<String, ParameterValue> resolved = new LinkedHashMap<>();
        for (ParameterDefinition parameter : applicable) {
            ParameterValue value = typed.get(parameter.name());
            if (value == null && !values.containsKey(parameter.name())) {
                value = parameter.defaultValue();
            }
            if (value != null) {
                resolved.put(parameter.name(), value);
            }
        }

        // S6 — 양변이 모두 확정된 제약만 판정한다.
        for (ParameterConstraint constraint : definition.parameterConstraints()) {
            ParameterValue left = resolved.get(constraint.left());
            ParameterValue right = resolved.get(constraint.right());
            if (left == null || right == null) {
                continue;
            }
            if (!holds(constraint, left, right)) {
                violations.add(ParameterViolation.constraintViolated(constraint, left, right));
            }
        }

        if (!violations.isEmpty()) {
            return ValidationOutcome.rejected(violations);
        }

        // S7
        ResolvedParameters resolvedParameters = new ResolvedParameters(resolved);
        return ValidationOutcome.accepted(
                resolvedParameters,
                HistoryRequirementCalculator.requiredBars(definition, resolvedParameters));
    }

    /** 입력 스칼라를 파라미터 타입에 맞는 값으로 옮긴다. 옮길 수 없으면 타입 불일치다. */
    private static Optional<ParameterValue> convert(ParameterDefinition parameter, Object raw) {
        return switch (parameter.type()) {
            case INTEGER ->
                    asNumber(raw)
                            .filter(ParameterSetValidator::isIntegral)
                            .map(number -> ParameterValue.of(number.longValueExact()));
            case DECIMAL -> asNumber(raw).map(ParameterValue::of);
            case BOOLEAN ->
                    raw instanceof Boolean flag
                            ? Optional.of(ParameterValue.of(flag.booleanValue()))
                            : Optional.empty();
            case ENUM ->
                    raw instanceof String text
                            ? Optional.of(ParameterValue.ofEnum(text))
                            : Optional.empty();
        };
    }

    /**
     * 숫자를 십진 표현으로 옮긴다. {@code new BigDecimal(double)}을 쓰지 않는 이유는 0.1이 0.1000000000000000055…로 들어와
     * 간격(step) 판정이 깨지기 때문이다.
     */
    private static Optional<BigDecimal> asNumber(Object raw) {
        if (raw instanceof BigDecimal decimal) {
            return Optional.of(decimal);
        }
        if (raw instanceof Boolean || !(raw instanceof Number number)) {
            return Optional.empty();
        }
        return Optional.of(new BigDecimal(number.toString()));
    }

    private static boolean isIntegral(BigDecimal value) {
        return value.stripTrailingZeros().scale() <= 0;
    }

    private static Optional<ParameterViolation> checkValueRange(
            ParameterDefinition parameter, ParameterValue value) {
        if (parameter.type().usesEnumValues()) {
            String text = ((ParameterValue.EnumValue) value).value();
            boolean allowed =
                    parameter.options().stream().map(EnumOption::value).anyMatch(text::equals);
            return allowed
                    ? Optional.empty()
                    : Optional.of(ParameterViolation.notInEnum(parameter, text));
        }

        Optional<NumericRange> range = parameter.range();
        if (range.isEmpty()) {
            return Optional.empty();
        }
        BigDecimal number = value.asNumber().orElseThrow();
        if (!range.get().contains(number)) {
            return Optional.of(ParameterViolation.outOfRange(parameter, number));
        }
        // 범위를 벗어난 값에 간격 위반까지 겹쳐 보고하지 않는다. 사용자가 고칠 것은 하나다.
        if (!range.get().matchesStep(number)) {
            return Optional.of(ParameterViolation.stepMismatch(parameter, number));
        }
        return Optional.empty();
    }

    private static boolean holds(
            ParameterConstraint constraint, ParameterValue left, ParameterValue right) {
        if (constraint.operator().requiresOrdering()) {
            BigDecimal leftNumber =
                    left.asNumber().orElseThrow(() -> notComparable(constraint, left));
            BigDecimal rightNumber =
                    right.asNumber().orElseThrow(() -> notComparable(constraint, right));
            return constraint.operator().holds(leftNumber.compareTo(rightNumber));
        }
        return constraint.operator().holds(compareForEquality(left, right));
    }

    private static int compareForEquality(ParameterValue left, ParameterValue right) {
        if (left.asNumber().isPresent() && right.asNumber().isPresent()) {
            // 30 과 30.0 은 같은 값이다. equals 로 비교하면 십진 스케일 차이가 결과를 가른다.
            return left.asNumber().get().compareTo(right.asNumber().get());
        }
        return left.raw().equals(right.raw()) ? 0 : 1;
    }

    private static IllegalStateException notComparable(
            ParameterConstraint constraint, ParameterValue value) {
        return new IllegalStateException(
                "크기 비교 제약의 양변은 수치형이어야 한다: %s %s %s, type=%s"
                        .formatted(
                                constraint.left(),
                                constraint.operator(),
                                constraint.right(),
                                value.type()));
    }
}
