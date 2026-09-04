package com.ssafy.thorp.strategy.application.service;

import com.ssafy.thorp.strategy.application.command.ValidateStrategyConfigCommand;
import com.ssafy.thorp.strategy.application.port.StrategyRegistry;
import com.ssafy.thorp.strategy.application.result.StrategyConfigValidationResult;
import com.ssafy.thorp.strategy.domain.model.StrategyCatalog;
import com.ssafy.thorp.strategy.domain.model.ValidationOutcome;
import com.ssafy.thorp.strategy.domain.policy.ParameterSetValidator;
import org.springframework.stereotype.Service;

/**
 * 전략 설정 판정 유스케이스.
 *
 * <p>판정 규칙 자체는 {@link ParameterSetValidator}(도메인)에 있다. 여기서는 카탈로그를 붙여 주고 결과를 유스케이스 출력으로 옮기기만 한다. 규칙을
 * 서비스에 두면 FE 미러와 대조할 지점이 흩어진다.
 */
@Service
public class StrategyConfigValidationService {

    private final StrategyRegistry registry;

    public StrategyConfigValidationService(StrategyRegistry registry) {
        this.registry = registry;
    }

    public StrategyConfigValidationResult validate(ValidateStrategyConfigCommand command) {
        StrategyCatalog catalog = registry.catalog();
        ValidationOutcome outcome =
                ParameterSetValidator.validate(
                        catalog,
                        command.strategyId(),
                        command.executionScope(),
                        command.parameters());
        Integer definitionVersion =
                catalog.find(command.strategyId())
                        .map(definition -> definition.definitionVersion())
                        .orElse(null);
        return StrategyConfigValidationResult.of(
                command.strategyId(), definitionVersion, command.executionScope(), outcome);
    }
}
