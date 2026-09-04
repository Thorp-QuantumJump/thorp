package com.ssafy.thorp.strategy.application.service;

import com.ssafy.thorp.strategy.application.port.StrategyRegistry;
import com.ssafy.thorp.strategy.application.result.StrategyDefinitionResult;
import com.ssafy.thorp.strategy.application.result.StrategySummaryResult;
import com.ssafy.thorp.strategy.domain.exception.StrategyNotFoundException;
import com.ssafy.thorp.strategy.domain.vo.ExecutionScope;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * 전략 카탈로그 조회 유스케이스.
 *
 * <p>DB를 쓰지 않는다. 전략 정의는 배포에 포함된 계약 파일이고 사용자가 바꿀 수 있는 대상이 아니므로(FR-022), 저장소가 아니라 {@link
 * StrategyRegistry}가 관리한다.
 */
@Service
public class StrategyCatalogService {

    private final StrategyRegistry registry;

    public StrategyCatalogService(StrategyRegistry registry) {
        this.registry = registry;
    }

    /**
     * 해당 실행 목적을 지원하는 전략 목록. 하나도 없으면 빈 목록이며 오류가 아니다.
     *
     * <p>지원하지 않는 전략을 목록에서 빼는 이유는, 고를 수 없는 것을 보여 주면 사용자가 고른 뒤에야 거부를 만나기 때문이다.
     */
    public List<StrategySummaryResult> findAll(ExecutionScope executionScope) {
        return registry.catalog().supporting(executionScope).stream()
                .map(StrategySummaryResult::from)
                .toList();
    }

    /** 전략 정의 전체. 없으면 {@link StrategyNotFoundException}(404). */
    public StrategyDefinitionResult findById(String strategyId) {
        return registry.catalog()
                .find(strategyId)
                .map(StrategyDefinitionResult::from)
                .orElseThrow(() -> new StrategyNotFoundException(strategyId));
    }
}
