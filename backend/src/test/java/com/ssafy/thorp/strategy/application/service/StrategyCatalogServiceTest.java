package com.ssafy.thorp.strategy.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ssafy.thorp.strategy.application.port.StrategyRegistry;
import com.ssafy.thorp.strategy.application.result.StrategyDefinitionResult;
import com.ssafy.thorp.strategy.application.result.StrategySummaryResult;
import com.ssafy.thorp.strategy.domain.StrategyFixtures;
import com.ssafy.thorp.strategy.domain.exception.StrategyNotFoundException;
import com.ssafy.thorp.strategy.domain.model.StrategyCatalog;
import com.ssafy.thorp.strategy.domain.vo.ExecutionScope;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 카탈로그 조회 유스케이스. 등재 자체는 레지스트리의 몫이라 여기서는 가짜 레지스트리를 쓴다. */
class StrategyCatalogServiceTest {

    private static StrategyCatalogService serviceOf(StrategyCatalog catalog) {
        StrategyRegistry registry = () -> catalog;
        return new StrategyCatalogService(registry);
    }

    @Test
    @DisplayName("목록은 실행 목적을 지원하는 전략만 담는다")
    void listFiltersByExecutionScope() {
        StrategyCatalogService service =
                serviceOf(StrategyCatalog.of(StrategyFixtures.allShippedStrategies()));

        assertThat(service.findAll(ExecutionScope.BACKTEST))
                .extracting(StrategySummaryResult::strategyId)
                .containsExactly("SMA_CROSSOVER", "RSI_REVERSION", "MACD_SIGNAL", "MOMENTUM");
        // MACD_SIGNAL 은 BACKTEST 전용이라 LIVE 목록에서 빠진다.
        assertThat(service.findAll(ExecutionScope.LIVE))
                .extracting(StrategySummaryResult::strategyId)
                .containsExactly("SMA_CROSSOVER", "RSI_REVERSION", "MOMENTUM");
    }

    @Test
    @DisplayName("목록 항목은 파라미터 개수까지만 담는다")
    void summaryCarriesParameterCount() {
        StrategyCatalogService service =
                serviceOf(StrategyCatalog.of(List.of(StrategyFixtures.smaCrossover())));

        StrategySummaryResult summary = service.findAll(ExecutionScope.BACKTEST).get(0);

        assertThat(summary.displayName()).isEqualTo("이동평균 교차");
        assertThat(summary.parameterCount()).isEqualTo(4);
        assertThat(summary.executionScopes()).containsExactly("BACKTEST", "LIVE");
        assertThat(summary.deprecated()).isFalse();
    }

    @Test
    @DisplayName("전략이 하나도 없으면 빈 목록이며 오류가 아니다")
    void emptyCatalogReturnsEmptyList() {
        assertThat(serviceOf(StrategyCatalog.empty()).findAll(ExecutionScope.BACKTEST)).isEmpty();
    }

    @Test
    @DisplayName("단건 조회는 정의 전체를 직렬화 가능한 형태로 돌려준다")
    void findByIdReturnsFullDefinition() {
        StrategyCatalogService service =
                serviceOf(StrategyCatalog.of(List.of(StrategyFixtures.smaCrossover())));

        StrategyDefinitionResult result = service.findById("SMA_CROSSOVER");

        assertThat(result.strategyId()).isEqualTo("SMA_CROSSOVER");
        assertThat(result.parameters()).hasSize(4);
        assertThat(result.parameters().get(0).numericRange()).isNotNull();
        assertThat(result.parameters().get(0).enumValues()).isNull();
        assertThat(result.parameters().get(2).defaultValue()).isEqualTo(Boolean.FALSE);
        assertThat(result.parameters().get(3).enumValues()).hasSize(3);
        assertThat(result.minimumHistoryRequirement().rule()).isEqualTo("MAX_PARAMETERS");
        assertThat(result.changeHistory().get(0).changedAt()).isEqualTo("2026-09-03");
    }

    @Test
    @DisplayName("없는 전략을 조회하면 404로 이어지는 예외를 던진다")
    void findByIdRejectsUnknownStrategy() {
        StrategyCatalogService service = serviceOf(StrategyCatalog.empty());

        assertThatThrownBy(() -> service.findById("NO_SUCH_STRATEGY"))
                .isInstanceOf(StrategyNotFoundException.class)
                .hasMessageContaining("NO_SUCH_STRATEGY");
    }
}
