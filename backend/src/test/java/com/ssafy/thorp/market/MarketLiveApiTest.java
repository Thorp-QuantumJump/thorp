package com.ssafy.thorp.market;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ssafy.thorp.market.application.service.MarketService;
import com.ssafy.thorp.market.infrastructure.client.*;
import com.ssafy.thorp.market.infrastructure.config.MarketConfig;
import com.ssafy.thorp.market.presentation.controller.MarketController;
import com.ssafy.thorp.market.presentation.controller.MarketExceptionHandler;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/** 명시적으로 활성화한 경우에만 실제 키로 원천 API를 호출한다. DB는 필요 없다. */
@EnabledIfEnvironmentVariable(named = "MARKET_LIVE_TEST", matches = "true")
@SpringBootTest(
        classes = MarketLiveApiTest.LiveConfig.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class MarketLiveApiTest {
    @Configuration
    @EnableAutoConfiguration(
            exclude = {
                DataSourceAutoConfiguration.class,
                HibernateJpaAutoConfiguration.class,
                KafkaAutoConfiguration.class
            })
    @Import({
        MarketConfig.class,
        MarketHttpClient.class,
        EcosMarketClient.class,
        KrxMarketClient.class,
        KosisMarketClient.class,
        CustomsMarketClient.class,
        MarketService.class,
        MarketController.class,
        MarketExceptionHandler.class,
        com.ssafy.thorp.common.web.advice.GlobalExceptionHandler.class
    })
    static class LiveConfig {}

    @LocalServerPort int port;
    @Autowired ObjectMapper mapper;

    @Test
    void realEndpointsProduceSanitizedResults() throws Exception {
        var summaries = new ArrayList<Map<String, Object>>();
        var client = HttpClient.newHttpClient();
        List<String> slugs =
                List.of(
                        "usd-krw",
                        "ktb-3y",
                        "esi-cycle",
                        "m2",
                        "kospi",
                        "kosdaq",
                        "vkospi200",
                        "coincident-cycle",
                        "leading-cycle",
                        "core-cpi-yoy",
                        "exports-usd");
        for (String slug : slugs) {
            var request =
                    HttpRequest.newBuilder(
                                    URI.create("http://localhost:" + port + "/markets/" + slug))
                            .timeout(Duration.ofSeconds(12))
                            .GET()
                            .build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());
            var json = mapper.readTree(response.body());
            var summary = new LinkedHashMap<String, Object>();
            summary.put("endpoint", slug);
            summary.put("http", response.statusCode());
            summary.put("message", json.path("message").asText());
            if (json.path("result").isObject()) {
                summary.put("value", json.path("result").path("value").decimalValue());
                summary.put("unit", json.path("result").path("unit").asText());
                summary.put(
                        "observationDate", json.path("result").path("observationDate").asText());
            }
            summaries.add(summary);
        }
        Path report = Path.of("build/reports/market-live.json");
        Files.createDirectories(report.getParent());
        mapper.writerWithDefaultPrettyPrinter().writeValue(report.toFile(), summaries);
        assertThat(summaries).allSatisfy(row -> assertThat(row.get("http")).isIn(200, 503));
        // 이미 키가 검증된 ECOS 4개는 실제 성공까지 확인한다.
        assertThat(summaries.subList(0, 4))
                .allSatisfy(row -> assertThat(row.get("http")).isEqualTo(200));
    }
}
