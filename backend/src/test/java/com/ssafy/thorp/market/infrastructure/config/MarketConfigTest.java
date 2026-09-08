package com.ssafy.thorp.market.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class MarketConfigTest {
    @Test
    void existingEnvironmentNamesWorkWithoutApplicationYaml() {
        var env =
                new MockEnvironment()
                        .withProperty("KOSIS_API_KEY", "key=")
                        .withProperty("MARKET_CACHE_TTL", "2h")
                        .withProperty("MARKET_CACHE_MAX_AGE", "12h")
                        .withProperty("MARKET_FAILURE_COOLDOWN", "10s")
                        .withProperty("MARKET_REQUEST_BUDGET", "5s");
        var properties = new MarketConfig().marketApiProperties(env);
        assertThat(properties.getKosis().getApiKey()).isEqualTo("key=");
        assertThat(properties.getCacheTtl()).isEqualTo(Duration.ofHours(2));
        assertThat(properties.getCacheMaxAge()).isEqualTo(Duration.ofHours(12));
        assertThat(properties.getFailureCooldown()).isEqualTo(Duration.ofSeconds(10));
        assertThat(properties.getRequestBudget()).isEqualTo(Duration.ofSeconds(5));
    }
}
