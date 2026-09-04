package com.ssafy.thorp.strategy.presentation.response;

import com.ssafy.thorp.strategy.application.result.StrategyConfigValidationResult;
import com.ssafy.thorp.strategy.domain.model.ParameterViolation;
import java.util.List;
import java.util.Map;

/**
 * {@code POST /strategies/config/validate} 응답 본문.
 *
 * <p>판정 실패도 HTTP 200이다. 요청은 정상 처리됐고 "이 값 집합은 쓸 수 없다"가 답이기 때문이다. 400으로 내리면 FE가 폼 오류 표시와 통신 오류 처리를 같은
 * 경로에서 다뤄야 하고, 어느 파라미터가 왜 틀렸는지를 오류 메시지 하나에 눌러 담게 된다.
 *
 * <p>{@code resolvedParameters}와 {@code requiredHistoryBars}는 거부일 때 {@code null}이며, 필드 자체는 남는다 — 소비
 * 지점이 키의 유무가 아니라 값의 유무로 판단하게 한다.
 */
public record StrategyConfigValidationResponse(
        String strategyId,
        Integer definitionVersion,
        String executionScope,
        boolean valid,
        List<ParameterViolationResponse> violations,
        Map<String, Object> resolvedParameters,
        Integer requiredHistoryBars) {

    public static StrategyConfigValidationResponse from(StrategyConfigValidationResult result) {
        return new StrategyConfigValidationResponse(
                result.strategyId(),
                result.definitionVersion(),
                result.executionScope().name(),
                result.valid(),
                result.violations().stream().map(ParameterViolationResponse::from).toList(),
                result.resolvedParameters(),
                result.requiredHistoryBars());
    }

    /**
     * 위반 하나.
     *
     * @param code 기계가 분기할 닫힌 어휘
     * @param parameterName 위반한 파라미터. 전략 단위 위반은 {@code null}
     * @param message 사용자에게 보일 설명
     * @param detail 판정 근거. 예: {@code {"min":2,"max":120,"actual":300}}
     */
    public record ParameterViolationResponse(
            String code, String parameterName, String message, Map<String, Object> detail) {

        public static ParameterViolationResponse from(ParameterViolation violation) {
            return new ParameterViolationResponse(
                    violation.code().name(),
                    violation.parameterName(),
                    violation.message(),
                    violation.detail());
        }
    }
}
