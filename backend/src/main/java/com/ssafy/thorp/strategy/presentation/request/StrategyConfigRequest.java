package com.ssafy.thorp.strategy.presentation.request;

import com.fasterxml.jackson.databind.JsonNode;
import com.ssafy.thorp.strategy.application.command.ValidateStrategyConfigCommand;
import com.ssafy.thorp.strategy.domain.vo.ExecutionScope;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 사용자가 고른 전략과 수정한 파라미터 값.
 *
 * <pre>
 * {
 *   "strategyId": "MOMENTUM",
 *   "executionScope": "BACKTEST",
 *   "parameters": { "lookbackDays": 60, "entryThreshold": 0.05 }
 * }
 * </pre>
 *
 * <p>파라미터 값을 {@link JsonNode}로 받는 이유는 두 가지다. 첫째, 값의 타입이 전략마다 다르므로 미리 고정할 수 없다. 둘째, 숫자를 {@code
 * double}로 받으면 0.05가 이진 오차와 함께 들어와 간격(step) 판정이 흔들린다. 노드로 받아 십진 표현({@code BigDecimal})으로 옮긴다.
 *
 * <p>Bean Validation은 <b>요청의 형태</b>만 본다. 값이 전략 정의에 맞는지는 판정기의 몫이고, 그 결과는 오류가 아니라 응답 본문에 담긴다.
 *
 * @param strategyId 전략 식별자
 * @param executionScope 실행 목적. 생략하면 {@link ExecutionScope#BACKTEST}
 * @param parameters 사용자가 지정한 값. 생략한 선택 파라미터는 정의의 기본값으로 채워진다
 */
public record StrategyConfigRequest(
        @NotBlank String strategyId,
        ExecutionScope executionScope,
        @NotNull Map<String, JsonNode> parameters) {

    public ValidateStrategyConfigCommand toCommand() {
        Map<String, Object> values = new LinkedHashMap<>();
        parameters.forEach((name, node) -> values.put(name, toScalar(node)));
        return new ValidateStrategyConfigCommand(
                strategyId,
                executionScope == null ? ExecutionScope.BACKTEST : executionScope,
                values);
    }

    /** JSON 스칼라를 도메인이 읽는 값으로 옮긴다. 스칼라가 아닌 것은 그대로 넘겨 타입 불일치로 판정되게 둔다. */
    private static Object toScalar(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isNumber()) {
            return node.decimalValue();
        }
        if (node.isBoolean()) {
            return node.booleanValue();
        }
        if (node.isTextual()) {
            return node.textValue();
        }
        return node;
    }
}
