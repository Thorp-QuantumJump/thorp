package com.ssafy.thorp.strategy.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ssafy.thorp.strategy.domain.StrategyFixtures;
import com.ssafy.thorp.strategy.domain.vo.ExecutionScope;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class StrategyCatalogTest {

    @Test
    @DisplayName("등재 순서를 유지하며 식별자로 조회한다")
    void findsByStrategyId() {
        StrategyCatalog catalog = StrategyCatalog.of(StrategyFixtures.allShippedStrategies());

        assertThat(catalog.size()).isEqualTo(4);
        assertThat(catalog.all())
                .extracting(StrategyDefinition::strategyId)
                .containsExactly("SMA_CROSSOVER", "RSI_REVERSION", "MACD_SIGNAL", "MOMENTUM");
        assertThat(catalog.find("RSI_REVERSION")).get().isEqualTo(StrategyFixtures.rsiReversion());
        assertThat(catalog.find("NO_SUCH_STRATEGY")).isEmpty();
        assertThat(catalog.contains("MOMENTUM")).isTrue();
    }

    @Test
    @DisplayName("전략이 하나도 없는 카탈로그는 정상이며 빈 목록을 반환한다")
    void emptyCatalogIsValid() {
        StrategyCatalog catalog = StrategyCatalog.empty();

        assertThat(catalog.isEmpty()).isTrue();
        assertThat(catalog.all()).isEmpty();
        assertThat(catalog.find("SMA_CROSSOVER")).isEmpty();
        assertThat(catalog.supporting(ExecutionScope.BACKTEST)).isEmpty();
    }

    @Test
    @DisplayName("실행 목적을 지원하는 전략만 골라낸다")
    void filtersBySupportedExecutionScope() {
        StrategyCatalog catalog = StrategyCatalog.of(StrategyFixtures.allShippedStrategies());

        assertThat(catalog.supporting(ExecutionScope.BACKTEST)).hasSize(4);
        assertThat(catalog.supporting(ExecutionScope.LIVE))
                .extracting(StrategyDefinition::strategyId)
                .containsExactly("SMA_CROSSOVER", "RSI_REVERSION", "MOMENTUM");
    }

    @Test
    @DisplayName("식별자가 겹치면 어느 쪽도 임의로 고르지 않고 거부한다")
    void rejectsDuplicateStrategyId() {
        List<StrategyDefinition> duplicated =
                List.of(StrategyFixtures.momentum(), StrategyFixtures.momentum());

        assertThatThrownBy(() -> StrategyCatalog.of(duplicated))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("MOMENTUM");
    }

    @Test
    @DisplayName("카탈로그는 불변이다")
    void isImmutable() {
        StrategyCatalog catalog = StrategyCatalog.of(StrategyFixtures.allShippedStrategies());

        assertThatThrownBy(() -> catalog.definitions().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
