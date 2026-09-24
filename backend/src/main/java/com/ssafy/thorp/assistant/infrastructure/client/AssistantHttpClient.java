package com.ssafy.thorp.assistant.infrastructure.client;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ssafy.thorp.assistant.infrastructure.config.AssistantModelProperties;
import com.ssafy.thorp.common.exception.BusinessException;
import com.ssafy.thorp.common.exception.ErrorCode;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * GMS 게이트웨이로 JSON을 POST 한다({@code MarketHttpClient} 관례). 호출마다 받은 {@code timeout}이 곧 읽기 제한이다 — 전체
 * 데드라인의 남은 시간과 라운드 예산 중 작은 쪽을 루프가 넘긴다.
 *
 * <p>어떤 실패도 {@code ASSISTANT_UNAVAILABLE}(503) 하나다. 예외 메시지는 그대로 응답 본문에 실리므로 사유는 로그에만 남기고 메시지는 코드의
 * 고정 문구를 쓴다. 로그에도 요청·응답 본문과 키는 남기지 않는다(ADR-0008 D3).
 */
public class AssistantHttpClient {
    private static final Logger log = LoggerFactory.getLogger(AssistantHttpClient.class);
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);

    private final HttpClient client;
    private final ObjectMapper mapper;
    private final URI endpoint;
    private final String authorization;

    public AssistantHttpClient(AssistantModelProperties properties, ObjectMapper mapper) {
        this.client =
                HttpClient.newBuilder()
                        .connectTimeout(CONNECT_TIMEOUT)
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .build();
        this.mapper = mapper;
        String base = properties.getBaseUrl().strip();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        this.endpoint = URI.create(base + "/v1/chat/completions");
        this.authorization = "Bearer " + properties.getApiKey().strip();
    }

    public JsonNode post(JsonNode body, Duration timeout) {
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw unavailable("no time budget left");
        }
        String payload;
        try {
            payload = mapper.writeValueAsString(body);
        } catch (JsonProcessingException exc) {
            throw unavailable("request serialization failed");
        }
        HttpRequest request =
                HttpRequest.newBuilder(endpoint)
                        .timeout(timeout)
                        .header("Authorization", authorization)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
                        .build();
        HttpResponse<String> response;
        try {
            response =
                    client.send(
                            request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (HttpTimeoutException exc) {
            throw unavailable("timeout after " + timeout.toMillis() + "ms");
        } catch (InterruptedException exc) {
            Thread.currentThread().interrupt();
            throw unavailable("interrupted");
        } catch (IOException | IllegalArgumentException exc) {
            throw unavailable("io failure: " + exc.getClass().getSimpleName());
        }
        int status = response.statusCode();
        if (status < 200 || status >= 300) {
            throw unavailable("http status " + status);
        }
        try {
            return mapper.readTree(response.body());
        } catch (IOException | IllegalArgumentException exc) {
            throw unavailable("response is not json");
        }
    }

    static BusinessException unavailable(String reason) {
        log.warn("assistant model unavailable: {}", reason);
        return new BusinessException(ErrorCode.ASSISTANT_UNAVAILABLE);
    }
}
