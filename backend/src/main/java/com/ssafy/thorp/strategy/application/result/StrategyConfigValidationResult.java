package com.ssafy.thorp.strategy.application.result;

import com.ssafy.thorp.strategy.domain.model.ParameterViolation;
import com.ssafy.thorp.strategy.domain.model.ValidationOutcome;
import com.ssafy.thorp.strategy.domain.vo.ExecutionScope;
import com.ssafy.thorp.strategy.domain.vo.ResolvedParameters;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 전략 설정 판정 결과.
 *
 * <p>판정 실패는 오류가 아니다. 요청 자체는 정상 처리됐고 "이 값 집합은 쓸 수 없다"는 답을 돌려주는 것이므로, 실패해도 이 결과를 반환한다. 어느 파라미터가 왜
 * 잘못됐는지는 {@code violations}가 전부 담는다.
 *
 * @param strategyId 판정 대상 전략. 카탈로그에 없는 전략이면 요청에 실려 온 값을 그대로 돌려준다
 * @param definitionVersion 판정에 쓴 정의의 버전. 전략을 찾지 못했으면 {@code null}
 * @param executionScope 판정에 쓴 실행 목적
 * @param valid 위반이 하나도 없으면 {@code true}
 * @param violations 발견된 모든 위반
 * @param resolvedParameters 기본값이 채워진 확정 값 집합. 거부면 {@code null}
 * @param requiredHistoryBars 확정 값 기준 최소 이력 요구량. 거부면 {@code null}
 */
public record StrategyConfigValidationResult(
        String strategyId,
        Integer definitionVersion,
        ExecutionScope executionScope,
        boolean valid,
        List<ParameterViolation> violations,
        Map<String, Object> resolvedParameters,
        Integer requiredHistoryBars) {

    public static StrategyConfigValidationResult of(
            String strategyId,
            Integer definitionVersion,
            ExecutionScope executionScope,
            ValidationOutcome outcome) {
        return new StrategyConfigValidationResult(
                strategyId,
                definitionVersion,
                executionScope,
                outcome.valid(),
                outcome.violations(),
                outcome.resolved().map(StrategyConfigValidationResult::toRawValues).orElse(null),
                outcome.requiredHistoryBars());
    }

    private static Map<String, Object> toRawValues(ResolvedParameters resolved) {
        Map<String, Object> values = new LinkedHashMap<>();
        resolved.values().forEach((name, value) -> values.put(name, value.raw()));
        return values;
    }
}
