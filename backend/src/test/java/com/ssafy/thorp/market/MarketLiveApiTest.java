package com.ssafy.thorp.market;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ssafy.thorp.market.application.service.MarketSeriesService;
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
import org.springframework.boot.actuate.autoconfigure.security.servlet.ManagementWebSecurityAutoConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * 명시적으로 활성화한 경우에만 실제 키로 원천 API를 호출한다. DB는 필요 없다.
 *
 * <p>CI는 {@code MARKET_LIVE_TEST}를 넣지 않으므로 늘 건너뛴다. 키를 바꾼 뒤 사람이 직접 돌리는 수동 시험이고, 실행 절차는 {@code
 * docs/specs/market-api.md}에 있다. 늘 건너뛰는 탓에 컨텍스트 구성이 운영과 어긋나도 드러나지 않으니, 컨트롤러가 새 의존성을 받으면 아래 목록에도
 * 더한다.
 */
@EnabledIfEnvironmentVariable(named = "MARKET_LIVE_TEST", matches = "true")
// 공통 설정의 news health 그룹은 뉴스 빈을 가리킨다. 시장 빈만 올리는 이 컨텍스트에는 그 빈이 없어 그룹 검증을 끈다.
@SpringBootTest(
        classes = MarketLiveApiTest.LiveConfig.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "management.endpoint.health.validate-group-membership=false")
class MarketLiveApiTest {
    @Configuration
    // 보안 자동 설정도 뺀다. 앱의 SecurityConfig 없이 기본 설정만 뜨면 모든 요청이 401이 되어 원천을 부르기도 전에 끝난다.
    @EnableAutoConfiguration(
            exclude = {
                DataSourceAutoConfiguration.class,
                HibernateJpaAutoConfiguration.class,
                KafkaAutoConfiguration.class,
                SecurityAutoConfiguration.class,
                ManagementWebSecurityAutoConfiguration.class
            })
    @Import({
        MarketConfig.class,
        MarketHttpClient.class,
        EcosMarketClient.class,
        KrxMarketClient.class,
        KosisMarketClient.class,
        CustomsMarketClient.class,
        MarketService.class,
        // 컨트롤러가 일별 지표 조회도 맡아 이 서비스 없이는 컨텍스트가 뜨지 않는다. 갱신 스케줄러는 넣지 않는다.
        MarketSeriesService.class,
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
