package com.ssafy.thorp.strategy.infrastructure.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ssafy.thorp.strategy.domain.model.ParameterDefinition;
import com.ssafy.thorp.strategy.domain.model.StrategyCatalog;
import com.ssafy.thorp.strategy.domain.model.StrategyDefinition;
import com.ssafy.thorp.strategy.domain.vo.ExecutionScope;
import com.ssafy.thorp.strategy.domain.vo.ParameterType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 배포되는 정의가 실제로 도메인 객체로 올라오는지, 그리고 깨진 정의 하나가 나머지를 막지 않는지 확인한다. */
class ClasspathStrategyRegistryTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static StrategyCatalog catalogOf(String pattern) {
        return new ClasspathStrategyRegistry(MAPPER, pattern).catalog();
    }

    @Test
    @DisplayName("배포된 전략 정의 4종이 모두 카탈로그에 오른다")
    void loadsShippedDefinitions() {
        StrategyCatalog catalog = catalogOf(ClasspathStrategyRegistry.DEFAULT_LOCATION_PATTERN);

        assertThat(catalog.definitions().keySet())
                .containsExactlyInAnyOrder(
                        "SMA_CROSSOVER", "RSI_REVERSION", "MACD_SIGNAL", "MOMENTUM");
        assertThat(catalog.all())
                .allSatisfy(definition -> assertThat(definition.changeHistory()).isNotEmpty());
    }

    @Test
    @DisplayName("JSON의 타입·범위·기본값이 도메인 값으로 그대로 옮겨진다")
    void mapsParameterDetails() {
        StrategyDefinition sma =
                catalogOf(ClasspathStrategyRegistry.DEFAULT_LOCATION_PATTERN)
                        .find("SMA_CROSSOVER")
                        .orElseThrow();

        assertThat(sma.displayName()).isEqualTo("이동평균 교차");
        assertThat(sma.executionScopes())
                .containsExactlyInAnyOrder(ExecutionScope.BACKTEST, ExecutionScope.LIVE);
        assertThat(sma.parameterNames())
                .containsExactly("shortPeriod", "longPeriod", "allowShort", "rebalancePeriod");

        ParameterDefinition shortPeriod = sma.parameter("shortPeriod").orElseThrow();
        assertThat(shortPeriod.type()).isEqualTo(ParameterType.INTEGER);
        assertThat(shortPeriod.required()).isTrue();
        assertThat(shortPeriod.hasDefault()).isFalse();
        assertThat(shortPeriod.range().orElseThrow().min()).isEqualByComparingTo("2");
        assertThat(shortPeriod.range().orElseThrow().max()).isEqualByComparingTo("120");
        assertThat(shortPeriod.range().orElseThrow().step()).isEqualByComparingTo("1");

        ParameterDefinition rebalance = sma.parameter("rebalancePeriod").orElseThrow();
        assertThat(rebalance.type()).isEqualTo(ParameterType.ENUM);
        assertThat(rebalance.defaultValue().raw()).isEqualTo("DAILY");
        assertThat(rebalance.options()).hasSize(3);

        assertThat(sma.parameterConstraints()).hasSize(1);
        assertThat(sma.minimumHistoryRequirement().targetParameters())
                .containsExactly("shortPeriod", "longPeriod");
    }

    @Test
    @DisplayName("깨진 정의는 제외하고 나머지는 정상 제공한다")
    void skipsBrokenDefinitions() {
        StrategyCatalog catalog = catalogOf("classpath*:broken-strategies/*.json");

        // 같은 디렉터리에 깨진 JSON·불변식 위반·파일명 불일치가 함께 있어도 성한 정의는 올라온다.
        assertThat(catalog.definitions().keySet()).containsExactly("SAMPLE_OK");
    }

    @Test
    @DisplayName("정의가 하나도 없으면 빈 카탈로그이며 기동에 성공한다")
    void emptyCatalogIsNotAnError() {
        assertThat(catalogOf("classpath*:no-such-directory/*.json").isEmpty()).isTrue();
    }

    @Test
    @DisplayName("식별자가 겹치면 충돌한 정의를 모두 제외한다")
    void excludesAllDuplicates() {
        assertThat(catalogOf("classpath*:dup-strategies-*/*.json").isEmpty()).isTrue();
    }
}
