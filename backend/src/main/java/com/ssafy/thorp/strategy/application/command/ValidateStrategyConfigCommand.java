package com.ssafy.thorp.strategy.application.command;

import com.ssafy.thorp.strategy.domain.vo.ExecutionScope;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 전략 설정 판정 요청. HTTP 요청 DTO를 애플리케이션 계층으로 들이지 않기 위한 입력 타입이다.
 *
 * @param strategyId 판정할 전략
 * @param executionScope 실행 목적. 생략된 요청은 컨트롤러가 {@link ExecutionScope#BACKTEST}로 채운다
 * @param parameters 사용자가 지정한 값. 값의 런타임 타입은 {@code Number}·{@code Boolean}·{@code String}이며 그 외는 타입
 *     불일치로 판정된다
 */
public record ValidateStrategyConfigCommand(
        String strategyId, ExecutionScope executionScope, Map<String, Object> parameters) {

    public ValidateStrategyConfigCommand {
        Objects.requireNonNull(strategyId, "전략 식별자는 null일 수 없다");
        Objects.requireNonNull(executionScope, "실행 목적은 null일 수 없다");
        Objects.requireNonNull(parameters, "파라미터 값 집합은 null일 수 없다(비어 있을 수는 있다)");
        // 입력 순서를 유지한 불변 복사본. UNKNOWN_PARAMETER 보고 순서가 요청마다 달라지지 않게 한다.
        parameters = Collections.unmodifiableMap(new LinkedHashMap<>(parameters));
    }
}
