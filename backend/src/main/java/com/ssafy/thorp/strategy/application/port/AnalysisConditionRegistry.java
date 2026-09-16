package com.ssafy.thorp.strategy.application.port;

import java.util.Objects;

/**
 * 전략 공통 분석 조건의 등록 정보(data-model §7).
 *
 * <p>전략별 축과 나란히 두지 않고 최상위에 따로 두는 이유는, 이 셋이 어느 전략을 골라도 같기 때문이다. 전략마다 되풀이해 적으면 값이 갈라진다.
 *
 * @param initialAmount 시작 금액(FR-011a 밖, research R6)
 * @param targetVolatility 목표 변동성. '목표 위험 맞춤' 시나리오에서만 쓴다(004 PD-18)
 * @param minEvaluationYears 최소 평가 기간(연). 워밍업과 별개로 FR-011의 판정에 쓴다(FR-011a)
 */
public record AnalysisConditionRegistry(
        AnalysisCondition initialAmount,
        AnalysisCondition targetVolatility,
        AnalysisCondition minEvaluationYears) {

    public AnalysisConditionRegistry {
        Objects.requireNonNull(initialAmount, "initialAmount must not be null");
        Objects.requireNonNull(targetVolatility, "targetVolatility must not be null");
        Objects.requireNonNull(minEvaluationYears, "minEvaluationYears must not be null");
    }
}
