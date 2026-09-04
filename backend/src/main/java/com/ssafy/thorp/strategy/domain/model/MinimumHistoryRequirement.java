package com.ssafy.thorp.strategy.domain.model;

import com.ssafy.thorp.strategy.domain.vo.HistoryRule;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * 전략이 요구하는 최소 이력의 양을 산출하는 선언(FR-010).
 *
 * <p>요구량은 파라미터 값에 따라 달라진다. 이동평균 기간을 200으로 늘리면 필요한 과거 데이터도 그만큼 늘어난다. 그렇다고 전략마다 계산 코드를 두면 소비 지점이 그
 * 코드를 실행할 수 있어야 하고, 그러면 입력 화면이 "이 기간으로는 데이터가 부족하다"를 미리 알려줄 수 없다. 그래서 <b>계산 방법 자체를 데이터로</b> 선언한다.
 *
 * <p>규칙 집합({@link HistoryRule})은 닫혀 있고 자유 수식은 없다. 현재 규칙으로 표현할 수 없는 전략이 나타나면 그 전략만을 위한 예외 계산을 두지 않고
 * 규칙 어휘를 넓힌다.
 *
 * <p>단위는 언제나 {@code bars}다. {@code bars}를 실제 달력 일자로 환산하는 일(거래일 달력 적용, 시작일 이전 데이터 존재 여부)은 이 계약의 범위
 * 밖이며 백테스트 조건 설정 기능이 다룬다.
 *
 * <p>실제 산출은 {@code domain.policy}의 계산기가 수행한다. 이 record는 무엇을 어떻게 계산할지에 대한 <b>선언</b>만 담는다.
 *
 * @param rule 산출 규칙
 * @param value {@link HistoryRule#FIXED}의 고정 상수. 그 외 규칙에서는 {@code null}
 * @param parameters 대상 파라미터 이름. {@code FIXED}에서는 {@code null}
 * @param offset 규칙 결과에 더할 고정 여유분
 * @param unit 단위. 항상 {@code "bars"}
 */
public record MinimumHistoryRequirement(
        HistoryRule rule, Integer value, List<String> parameters, int offset, String unit) {

    /** 이 계약이 인정하는 유일한 단위. */
    public static final String BARS = "bars";

    public MinimumHistoryRequirement {
        Objects.requireNonNull(rule, "최소 이력 규칙은 null일 수 없다");

        if (!BARS.equals(unit)) {
            throw new IllegalArgumentException("최소 이력의 단위는 'bars'여야 한다: " + unit);
        }
        if (offset < 0) {
            throw new IllegalArgumentException("offset은 0 이상이어야 한다: " + offset);
        }

        if (rule.usesFixedValue()) {
            if (value == null) {
                throw new IllegalArgumentException("FIXED 규칙은 value가 있어야 한다");
            }
            if (value < 0) {
                throw new IllegalArgumentException("FIXED 규칙의 value는 0 이상이어야 한다: " + value);
            }
            if (parameters != null) {
                throw new IllegalArgumentException("FIXED 규칙은 대상 파라미터를 가질 수 없다");
            }
        } else {
            if (value != null) {
                throw new IllegalArgumentException("%s 규칙은 value를 가질 수 없다".formatted(rule));
            }
            if (parameters == null || parameters.isEmpty()) {
                throw new IllegalArgumentException("%s 규칙은 대상 파라미터가 하나 이상이어야 한다".formatted(rule));
            }
            if (parameters.size() > rule.maxTargetCount()) {
                throw new IllegalArgumentException(
                        "%s 규칙의 대상 파라미터는 %d개까지다: %s"
                                .formatted(rule, rule.maxTargetCount(), parameters));
            }
            Set<String> seen = new LinkedHashSet<>();
            for (String parameter : parameters) {
                if (!ParameterDefinition.NAME_PATTERN.matcher(parameter).matches()) {
                    throw new IllegalArgumentException("최소 이력 규칙의 대상이 파라미터 이름이 아니다: " + parameter);
                }
                // SUM_PARAMETERS에서 같은 이름이 두 번 들어가면 그 값이 두 번 더해진다. 의도한 계산이
                // 아니라 오타일 가능성이 크고, 의도한 것이라면 offset으로 표현해야 한다.
                if (!seen.add(parameter)) {
                    throw new IllegalArgumentException("최소 이력 규칙의 대상이 중복됐다: " + parameter);
                }
            }
        }

        parameters = parameters == null ? null : List.copyOf(parameters);
    }

    /** 파라미터와 무관한 고정 요구량. */
    public static MinimumHistoryRequirement fixed(int bars, int offset) {
        return new MinimumHistoryRequirement(HistoryRule.FIXED, bars, null, offset, BARS);
    }

    /** 파라미터 하나의 값을 그대로 쓰는 요구량. */
    public static MinimumHistoryRequirement ofParameter(String parameterName, int offset) {
        return new MinimumHistoryRequirement(
                HistoryRule.PARAMETER, null, List.of(parameterName), offset, BARS);
    }

    /** 여러 파라미터 값 중 최댓값을 쓰는 요구량. */
    public static MinimumHistoryRequirement maxOf(List<String> parameterNames, int offset) {
        return new MinimumHistoryRequirement(
                HistoryRule.MAX_PARAMETERS, null, parameterNames, offset, BARS);
    }

    /** 여러 파라미터 값의 합을 쓰는 요구량. */
    public static MinimumHistoryRequirement sumOf(List<String> parameterNames, int offset) {
        return new MinimumHistoryRequirement(
                HistoryRule.SUM_PARAMETERS, null, parameterNames, offset, BARS);
    }

    public Optional<Integer> fixedValue() {
        return Optional.ofNullable(value);
    }

    /** 대상 파라미터 이름. {@code FIXED}면 빈 목록. */
    public List<String> targetParameters() {
        return parameters == null ? List.of() : parameters;
    }
}
