package com.ssafy.thorp.market.infrastructure.client;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ssafy.thorp.market.application.port.MarketDeadline;
import com.ssafy.thorp.market.domain.exception.MarketDataException;
import com.ssafy.thorp.market.domain.exception.MarketDataException.Reason;
import com.ssafy.thorp.market.infrastructure.config.MarketApiProperties;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class MarketHttpClientTest {
    @Test
    void actualHttpQueryPreservesBase64AndRedactsFailure() throws Exception {
        var received = new AtomicReference<String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/",
                exchange -> {
                    received.set(exchange.getRequestURI().getRawQuery());
                    exchange.sendResponseHeaders(401, -1);
                    exchange.close();
                });
        server.start();
        try {
            var uri =
                    MarketHttpClient.query(
                            "http://127.0.0.1:" + server.getAddress().getPort() + "/",
                            Map.of("apiKey", "ab+c/="));
            var client = new MarketHttpClient(new MarketApiProperties(), new ObjectMapper());
            assertThatThrownBy(
                            () ->
                                    client.json(
                                            uri,
                                            Map.of(),
                                            new MarketDeadline(Duration.ofSeconds(2))))
                    .isInstanceOfSatisfying(
                            MarketDataException.class,
                            e -> {
                                assertThat(e.reason()).isEqualTo(Reason.AUTHENTICATION);
                                assertThat(e.getMessage()).doesNotContain("ab+c", "http");
                                assertThat(e.getCause()).isNull();
                            });
            assertThat(URLDecoder.decode(received.get(), java.nio.charset.StandardCharsets.UTF_8))
                    .isEqualTo("apiKey=ab+c/=");
        } finally {
            server.stop(0);
        }
    }
}
