package com.ssafy.thorp.market.infrastructure.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ssafy.thorp.market.application.port.MarketDeadline;
import com.ssafy.thorp.market.domain.exception.MarketDataException;
import com.ssafy.thorp.market.domain.exception.MarketDataException.Reason;
import com.ssafy.thorp.market.infrastructure.config.MarketApiProperties;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

/** 원천 요청은 리다이렉트하지 않으며 인증 URL과 원문 예외를 외부에 전달하지 않는다. */
@Component
public class MarketHttpClient {
    private final HttpClient client;
    private final ObjectMapper mapper;
    private final Duration readTimeout;

    public MarketHttpClient(MarketApiProperties properties, ObjectMapper mapper) {
        this.client =
                HttpClient.newBuilder()
                        .connectTimeout(properties.getConnectTimeout())
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .build();
        this.readTimeout = properties.getReadTimeout();
        this.mapper = mapper;
    }

    public JsonNode json(URI uri, Map<String, String> headers, MarketDeadline deadline) {
        String body = text(uri, headers, deadline);
        try {
            return mapper.readTree(body);
        } catch (IOException | IllegalArgumentException e) {
            throw new MarketDataException(Reason.INVALID_RESPONSE);
        }
    }

    public String text(URI uri, Map<String, String> headers, MarketDeadline deadline) {
        Duration remaining = deadline.remaining();
        Duration timeout = remaining.compareTo(readTimeout) < 0 ? remaining : readTimeout;
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(uri).GET().timeout(timeout);
            headers.forEach(builder::header);
            HttpResponse<String> response =
                    client.send(
                            builder.build(),
                            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            int status = response.statusCode();
            if (status == 401 || status == 403)
                throw new MarketDataException(Reason.AUTHENTICATION);
            if (status < 200 || status >= 300) throw new MarketDataException(Reason.UPSTREAM_ERROR);
            return response.body();
        } catch (HttpTimeoutException e) {
            throw new MarketDataException(Reason.TIMEOUT);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new MarketDataException(Reason.TIMEOUT);
        } catch (IOException | IllegalArgumentException e) {
            throw new MarketDataException(Reason.UPSTREAM_ERROR);
        }
    }

    public static URI query(String baseUrl, Map<String, String> parameters) {
        var builder = UriComponentsBuilder.fromUriString(baseUrl);
        Map<String, String> variables = new LinkedHashMap<>();
        parameters.forEach(
                (name, value) -> {
                    String variable = "p" + variables.size();
                    builder.queryParam(name, "{" + variable + "}");
                    variables.put(variable, value);
                });
        // URI 변수로 엄격히 인코딩해 Base64의 +, /, =를 그대로 전달한다.
        return builder.encode().buildAndExpand(variables).toUri();
    }

    public static String key(String value) {
        String key = value == null ? "" : value.trim();
        if (key.length() >= 2
                && ((key.startsWith("\"") && key.endsWith("\""))
                        || (key.startsWith("'") && key.endsWith("'"))))
            key = key.substring(1, key.length() - 1);
        if (key.isBlank()) throw new MarketDataException(Reason.NOT_CONFIGURED);
        return key;
    }
}
