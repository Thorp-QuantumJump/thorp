package com.ssafy.thorp.market.infrastructure.config;

import com.ssafy.thorp.market.application.service.MarketCachePolicy;
import com.ssafy.thorp.market.application.service.MarketSeriesPolicy;
import java.time.Clock;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.PropertySource;
import org.springframework.core.env.Environment;

@Configuration
@PropertySource(
        value = {"file:../.env", "file:.env"},
        ignoreResourceNotFound = true,
        encoding = "UTF-8")
public class MarketConfig {
    /** 외부 기관 키의 기존 환경변수 이름을 market 설정에 연결한다. */
    @Bean
    @ConfigurationProperties(prefix = "market")
    public MarketApiProperties marketApiProperties(Environment environment) {
        var properties = new MarketApiProperties();
        properties.getEcos().setApiKey(environment.getProperty("ECOS_API_KEY", ""));
        properties.getKrx().setApiKey(environment.getProperty("KRX_API_KEY", ""));
        properties.getKosis().setApiKey(environment.getProperty("KOSIS_API_KEY", ""));
        properties.getCustoms().setApiKey(environment.getProperty("DATA_GO_KR_SERVICE_KEY", ""));
        properties
                .getEcos()
                .setM2Table(
                        environment.getProperty(
                                "ECOS_M2_TABLE", properties.getEcos().getM2Table()));
        properties
                .getEcos()
                .setM2Item(
                        environment.getProperty("ECOS_M2_ITEM", properties.getEcos().getM2Item()));
        properties
                .getKrx()
                .setVolatilityIndexName(
                        environment.getProperty(
                                "KRX_VOLATILITY_INDEX_NAME",
                                properties.getKrx().getVolatilityIndexName()));
        properties.setCacheTtl(
                DurationStyle.detectAndParse(
                        environment.getProperty(
                                "MARKET_CACHE_TTL", properties.getCacheTtl().toString())));
        properties.setCacheMaxAge(
                DurationStyle.detectAndParse(
                        environment.getProperty(
                                "MARKET_CACHE_MAX_AGE", properties.getCacheMaxAge().toString())));
        properties.setFailureCooldown(
                DurationStyle.detectAndParse(
                        environment.getProperty(
                                "MARKET_FAILURE_COOLDOWN",
                                properties.getFailureCooldown().toString())));
        properties.setRequestBudget(
                DurationStyle.detectAndParse(
                        environment.getProperty(
                                "MARKET_REQUEST_BUDGET",
                                properties.getRequestBudget().toString())));
        return properties;
    }

    @Bean("marketClock")
    public Clock marketClock() {
        return Clock.systemUTC();
    }

    @Bean
    public MarketCachePolicy marketCachePolicy(MarketApiProperties properties) {
        return new MarketCachePolicy(
                properties.getCacheTtl(),
                properties.getCacheMaxAge(),
                properties.getFailureCooldown(),
                properties.getRequestBudget());
    }

    @Bean
    public MarketSeriesPolicy marketSeriesPolicy(MarketApiProperties properties) {
        var series = properties.getSeries();
        return new MarketSeriesPolicy(
                series.getWindow(),
                series.getLookbackDays(),
                series.getRefreshBudget(),
                properties.getCacheMaxAge());
    }
}
