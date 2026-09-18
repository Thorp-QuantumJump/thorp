package com.ssafy.thorp.strategy.application.result;

import com.ssafy.thorp.strategy.application.port.RegisteredChoice;
import com.ssafy.thorp.strategy.application.port.ValidationRule;
import java.util.List;

/**
 * {@code GET /strategies/registry}의 유스케이스 출력(contracts/strategy-registry-api.md §1).
 *
 * <p><b>필드 이름을 계약 JSON과 1:1로 맞춘다.</b> 화면은 이 응답 하나로 전략 카드·상세 설정 항목·허용 범위·기본값을 구성하고 전략 이름이나 설정 항목을
 * 코드에 따로 두지 않는다(FR-005). 이름이 어긋나면 계약 테스트가 깨진다.
 *
 * <p><b>{@code searchValues}는 담지 않는다.</b> 자동 탐색 후보는 007이 내부 포트로 직접 읽으며 003 화면은 쓸 일이 없다. 뜻과 비용이 다른 두
 * 목록을 함께 내리면 혼동만 생긴다(계약 §1, research R3).
 *
 * @param registryVersion 등록표 버전. 분석 요청에 그대로 실어 보낸다(FR-014)
 * @param provisional 값이 아직 팀 확정값이 아닌가. 화면이 그 사실을 계속 표시한다
 * @param analysisConditions 전략과 무관한 공통 분석 조건
 * @param strategies <b>활성 전략만.</b> 비활성 전략은 아예 실리지 않는다(계약 §1)
 */
public record StrategyRegistryResult(
        String registryVersion,
        boolean provisional,
        AnalysisConditionsView analysisConditions,
        List<StrategyView> strategies) {

    public StrategyRegistryResult {
        strategies = List.copyOf(strategies);
    }

    /**
     * 전략 공통 분석 조건(data-model §7).
     *
     * @param initialAmount 시작 금액
     * @param targetVolatility 목표 변동성. '목표 위험 맞춤' 시나리오에서만 쓴다(004 PD-18)
     * @param minEvaluationYears 최소 평가 기간(연). 워밍업과 별개로 FR-011의 판정에 쓴다(FR-011a)
     */
    public record AnalysisConditionsView(
            ConditionView initialAmount,
            ConditionView targetVolatility,
            ConditionView minEvaluationYears) {}

    /**
     * 분석 조건 하나의 허용 범위와 기본값.
     *
     * <p>기본값을 범위와 한 덩이로 담는다 — 둘이 흩어지면 기본값이 범위 밖으로 벗어난 채 화면이 열릴 수 있다(data-model §7).
     *
     * <p>목록형이면 {@code choices}만, 입력형이면 {@code min}·{@code max}·{@code step}만 채운다. 나머지는 null이고 응답에
     * 실리지 않는다.
     *
     * <p><b>후보는 값과 표시 문구를 함께 싣는다.</b> {@code minEvaluationYears}의 {@code 0}이 "0년"이 아니라 "제한 없음"이라는
     * 것은 등록표만 아는 사실이라, 값만 내려보내면 화면이 그 대응을 코드에 적게 된다(FR-005·FR-011a).
     *
     * @param defaultValue 계약 JSON에서는 {@code default}다. 자바 예약어라 이름을 바꿔 담고 표현 계층이 되돌린다
     */
    public record ConditionView(
            Object min,
            Object max,
            Object step,
            List<RegisteredChoice> choices,
            Object defaultValue,
            String unit,
            String usedBy) {

        public ConditionView {
            choices = choices == null ? null : List.copyOf(choices);
        }
    }

    /**
     * 전략 카드 하나.
     *
     * @param targetWeightSource {@code USER} | {@code EQUAL} | {@code COMPUTED}. 화면이 전략 ID로 분기해 목표
     *     비중의 출처를 정하지 않게 하는 값이다(계약 §1, research R7-1)
     * @param selectable 활성이되 지금 조건에서 고를 수 있는가. {@code universeSize}를 주면 기본 설정으로 유효성 제약을 평가한 결과다
     * @param unselectableReason {@code selectable}이 false일 때만. 위반한 규칙의 {@code detail} 그대로이며 화면이 문구를
     *     만들지 않는다
     * @param warmupTradingDays 기본 설정 기준 워밍업 거래일 수. 워밍업 규칙이 없으면 0이다
     * @param validationRules 화면이 실행 전에 그 자리에서 평가할 조건. {@code universeSize}와 무관하게 항상 실린다 — 규칙 목록이지
     *     위반 판정이 아니다(quickstart 3)
     * @param warmupRule 설정이 바뀔 때 화면이 다시 계산하는 규칙. 없으면 워밍업 0이다(data-model §6)
     */
    public record StrategyView(
            String strategyId,
            String name,
            String description,
            String targetWeightSource,
            boolean selectable,
            String unselectableReason,
            int warmupTradingDays,
            List<AxisView> axes,
            List<RuleView> validationRules,
            WarmupRuleView warmupRule) {

        public StrategyView {
            axes = List.copyOf(axes);
            validationRules = List.copyOf(validationRules);
        }
    }

    /**
     * 설정 항목 하나.
     *
     * @param description 항목의 의미 한 문장. 화면이 축 이름으로 분기해 설명을 고르지 않게 하는 자리다(research R7-1)
     * @param choices 목록형일 때만. 고를 수 없는 후보도 {@code selectable: false}로 남는다
     * @param range 입력형일 때만
     * @param defaultValue 등록표의 기본 시작값. 추천이나 최적값으로 표시하지 않는다(원칙 III)
     * @param appliesWhen 이 축이 뜻을 갖는 조건. 없으면 늘 그린다. 화면은 조건이 거짓인 축을 그리지도 요청에 싣지도 않는다(spec FR-003)
     * @param termId 013 용어 설명의 식별자. 없으면 도움말을 붙이지 않는다(spec FR-016)
     */
    public record AxisView(
            String name,
            String label,
            String description,
            String unit,
            InputType inputType,
            List<ChoiceView> choices,
            RangeView range,
            Object defaultValue,
            AxisConditionView appliesWhen,
            String termId) {

        public AxisView {
            choices = choices == null ? null : List.copyOf(choices);
        }
    }

    /** 축의 적용 조건. 결정 축 이름과 그 값이며 화면은 {@code equals}로만 견준다. */
    public record AxisConditionView(String axis, Object value) {}

    /** 축을 목록으로 그릴지 입력칸으로 그릴지. 허용 범위의 형태가 정하며 축의 의미는 보지 않는다(계약 §1). */
    public enum InputType {
        CHOICE,
        NUMBER
    }

    /**
     * 목록형 축의 후보 하나.
     *
     * @param label 화면 표시 문구. 등록 구조에 후보별 라벨 자리가 아직 없어 값의 형태를 보고 만든다
     * @param selectable 이 후보를 고를 수 있는가. false여도 <b>목록에서 빼지 않는다</b> — 왜 고를 수 없는지 사용자가 알아야 한다(계약 §1)
     */
    public record ChoiceView(Object value, String label, boolean selectable) {}

    /** 입력형 축의 허용 범위. */
    public record RangeView(Object min, Object max, Object step) {}

    /**
     * 유효성 규칙 한 건. <b>조건이 참이면 위반이다.</b>
     *
     * <p>화면은 규칙의 뜻을 해석하지 않고 네 필드만 기계적으로 평가한다. 축 이름이나 코드로 분기하면 그것이 전략 지식의 중복이다(계약 §1).
     */
    public record RuleView(
            String code,
            String detail,
            OperandView left,
            ValidationRule.Operator operator,
            OperandView right) {}

    /**
     * 비교 대상 하나. {@code axis}·{@code context}·{@code value} 중 하나만 채워져 있고 나머지는 응답에 실리지 않는다.
     *
     * <p>{@code multiplyByAxis}는 축 값에 곱할 다른 축이다(예: {@code maxWeight × topN}). 화면과 007 이 같은 판정을 하려면
     * 응답에도 실려야 한다(FR-005).
     */
    public record OperandView(
            String axis,
            String context,
            Object value,
            String multiplyByContext,
            String multiplyByAxis) {}

    /** 워밍업 산출 규칙. {@code sum}의 축 이름을 현재 값으로 바꿔 모두 더한다(data-model §6). */
    public record WarmupRuleView(List<Object> sum) {

        public WarmupRuleView {
            sum = List.copyOf(sum);
        }
    }
}
