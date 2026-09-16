package com.ssafy.thorp.strategy.presentation.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.ssafy.thorp.strategy.application.port.RegisteredChoice;
import com.ssafy.thorp.strategy.application.result.StrategyRegistryResult;
import java.util.List;

/**
 * {@code GET /strategies/registry} 응답 본문. 계약은 contracts/strategy-registry-api.md §1이고, 같은 내용이
 * {@code docs/specs/strategy-registry-api/examples/registry-response.json}에 픽스처로 있다.
 *
 * <p><b>값이 없는 필드는 키를 생략한다.</b> 목록형 축에 {@code range}를, 입력형 축에 {@code choices}를 {@code null}로 실어 보내면
 * 화면이 "둘 다 왔는데 무엇을 그릴까"를 따져야 한다. 어느 쪽인지는 {@code inputType} 하나가 말한다.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record StrategyRegistryResponse(
        String registryVersion,
        boolean provisional,
        AnalysisConditions analysisConditions,
        List<Strategy> strategies) {

    public static StrategyRegistryResponse from(StrategyRegistryResult result) {
        return new StrategyRegistryResponse(
                result.registryVersion(),
                result.provisional(),
                AnalysisConditions.from(result.analysisConditions()),
                result.strategies().stream().map(Strategy::from).toList());
    }

    /** 전략과 무관한 공통 분석 조건(data-model §7). */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record AnalysisConditions(
            Condition initialAmount, Condition targetVolatility, Condition minEvaluationYears) {

        public static AnalysisConditions from(StrategyRegistryResult.AnalysisConditionsView view) {
            return new AnalysisConditions(
                    Condition.from(view.initialAmount()),
                    Condition.from(view.targetVolatility()),
                    Condition.from(view.minEvaluationYears()));
        }
    }

    /**
     * 분석 조건 하나. 목록형이면 {@code choices}만, 입력형이면 {@code min}·{@code max}·{@code step}만 실린다.
     *
     * @param defaultValue 계약의 {@code default}다. 자바 예약어라 이름을 바꿔 담고 {@code @JsonProperty}로 되돌린다
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Condition(
            Object min,
            Object max,
            Object step,
            List<ConditionChoice> choices,
            @JsonProperty("default") Object defaultValue,
            String unit,
            String usedBy) {

        public static Condition from(StrategyRegistryResult.ConditionView view) {
            return new Condition(
                    view.min(),
                    view.max(),
                    view.step(),
                    view.choices() == null
                            ? null
                            : view.choices().stream().map(ConditionChoice::from).toList(),
                    view.defaultValue(),
                    view.unit(),
                    view.usedBy());
        }
    }

    /**
     * 분석 조건의 후보 하나.
     *
     * <p>전략 축의 {@link Choice}와 달리 {@code selectable}이 없다. 유효성 규칙은 전략 축에만 걸리므로 조건 후보에는 고를 수 없다는 판정이
     * 아예 없고, 늘 참인 필드를 실어 보내면 화면이 있지도 않은 판정을 따지게 된다.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ConditionChoice(Object value, String label) {

        public static ConditionChoice from(RegisteredChoice choice) {
            return new ConditionChoice(choice.value(), choice.label());
        }
    }

    /** 전략 카드 하나. <b>활성 전략만 실린다</b> — 비활성은 {@code selectable: false}가 아니라 목록에서 아예 빠진다. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Strategy(
            String strategyId,
            String name,
            String description,
            String targetWeightSource,
            boolean selectable,
            String unselectableReason,
            int warmupTradingDays,
            List<Axis> axes,
            List<Rule> validationRules,
            WarmupRule warmupRule) {

        public static Strategy from(StrategyRegistryResult.StrategyView view) {
            return new Strategy(
                    view.strategyId(),
                    view.name(),
                    view.description(),
                    view.targetWeightSource(),
                    view.selectable(),
                    view.unselectableReason(),
                    view.warmupTradingDays(),
                    view.axes().stream().map(Axis::from).toList(),
                    view.validationRules().stream().map(Rule::from).toList(),
                    WarmupRule.from(view.warmupRule()));
        }
    }

    /**
     * 설정 항목 하나. {@code inputType}이 {@code CHOICE}면 {@code choices}가, {@code NUMBER}면 {@code range}가
     * 실린다.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Axis(
            String name,
            String label,
            String description,
            String unit,
            String inputType,
            List<Choice> choices,
            Range range,
            Object defaultValue,
            AxisCondition appliesWhen,
            String termId) {

        public static Axis from(StrategyRegistryResult.AxisView view) {
            return new Axis(
                    view.name(),
                    view.label(),
                    view.description(),
                    view.unit(),
                    view.inputType().name(),
                    view.choices() == null
                            ? null
                            : view.choices().stream().map(Choice::from).toList(),
                    Range.from(view.range()),
                    view.defaultValue(),
                    AxisCondition.from(view.appliesWhen()),
                    view.termId());
        }
    }

    /** 축의 적용 조건(계약 §1 {@code appliesWhen}). 조건이 없는 축에는 실리지 않는다. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record AxisCondition(String axis, Object value) {

        public static AxisCondition from(StrategyRegistryResult.AxisConditionView view) {
            return view == null ? null : new AxisCondition(view.axis(), view.value());
        }
    }

    /** 목록형 축의 후보 하나. 고를 수 없는 후보도 <b>목록에 남는다</b>(계약 §1). */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Choice(Object value, String label, boolean selectable) {

        public static Choice from(StrategyRegistryResult.ChoiceView view) {
            return new Choice(view.value(), view.label(), view.selectable());
        }
    }

    /** 입력형 축의 허용 범위. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Range(Object min, Object max, Object step) {

        public static Range from(StrategyRegistryResult.RangeView view) {
            return view == null ? null : new Range(view.min(), view.max(), view.step());
        }
    }

    /** 유효성 규칙 한 건. <b>조건이 참이면 위반이다.</b> 화면이 네 필드만 기계적으로 평가한다. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Rule(String code, String detail, Operand left, String operator, Operand right) {

        public static Rule from(StrategyRegistryResult.RuleView view) {
            return new Rule(
                    view.code(),
                    view.detail(),
                    Operand.from(view.left()),
                    view.operator().name(),
                    Operand.from(view.right()));
        }
    }

    /** 비교 대상 하나. {@code axis}·{@code context}·{@code value} 중 채워진 것만 실린다. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Operand(String axis, String context, Object value, String multiplyByContext) {

        public static Operand from(StrategyRegistryResult.OperandView view) {
            return new Operand(view.axis(), view.context(), view.value(), view.multiplyByContext());
        }
    }

    /** 워밍업 산출 규칙. 워밍업이 없는 전략은 이 필드가 실리지 않으며 화면은 0으로 읽는다(data-model §6). */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record WarmupRule(List<Object> sum) {

        public static WarmupRule from(StrategyRegistryResult.WarmupRuleView view) {
            return view == null ? null : new WarmupRule(view.sum());
        }
    }
}
