package com.ssafy.thorp.news.infrastructure.client;

import com.ssafy.thorp.news.application.port.NewsSourceClient;
import com.ssafy.thorp.news.infrastructure.client.NewsHttpSecurityPolicy.SourceKind;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * JDK HTTP 클라이언트로 외부 뉴스원을 호출한다.
 *
 * <p>redirect를 자동으로 따르지 않는다. hop마다 {@link NewsHttpSecurityPolicy}로 scheme와 host를 다시 검사해야 하는데, 자동
 * 추적은 그 검사를 건너뛰기 때문이다.
 *
 * <p>응답 본문은 한도까지만 읽고 넘으면 스트림을 끊는다. 먼저 다 읽고 크기를 재면 한도가 방어가 되지 않는다.
 *
 * <p>오류는 분류 코드만 남긴다. 주소·인증 정보·응답 원문을 예외 메시지에 넣지 않는다(spec FR-021).
 */
@Component
public class JdkNewsHttpClient implements NewsHttpClient {

    /** 연결·읽기 시간 초과. 다음 회차로 미룬다. */
    public static final String TIMEOUT = "TIMEOUT";

    /** 연결 자체가 되지 않았다. 다음 회차로 미룬다. */
    public static final String CONNECTION_FAILED = "CONNECTION_FAILED";

    /** 호출 간격 제한. `Retry-After`를 존중한다. */
    public static final String RATE_LIMITED = "RATE_LIMITED";

    /** 뉴스원 쪽 일시 오류. */
    public static final String SERVER_ERROR = "SERVER_ERROR";

    /** 인증·권한 설정 오류. 재시도하지 않고 비활성화 후보로 둔다. */
    public static final String UNAUTHORIZED = "UNAUTHORIZED";

    /** 그 밖의 4xx. 재시도해도 같다. */
    public static final String CLIENT_ERROR = "CLIENT_ERROR";

    private final NewsHttpSecurityPolicy policy;
    private final HttpClient httpClient;

    public JdkNewsHttpClient(NewsHttpSecurityPolicy policy) {
        this.policy = policy;
        this.httpClient =
                HttpClient.newBuilder()
                        .connectTimeout(policy.connectTimeout())
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .build();
    }

    @Override
    public NewsHttpResponse get(URI url, SourceKind kind, String etag, String lastModified) {
        URI target = url;
        for (int hop = 0; hop <= policy.maxRedirects(); hop++) {
            HttpResponse<InputStream> response = send(target, etag, lastModified);
            int status = response.statusCode();

            if (isRedirect(status)) {
                String location =
                        response.headers()
                                .firstValue("location")
                                .orElseThrow(
                                        () ->
                                                NewsHttpSecurityPolicy.reject(
                                                        NewsHttpSecurityPolicy.MALFORMED_URL));
                target = policy.requireAllowedRedirect(location, target, hop + 1, kind);
                closeQuietly(response.body());
                continue;
            }

            return toResponse(response, status);
        }
        throw NewsHttpSecurityPolicy.reject(NewsHttpSecurityPolicy.TOO_MANY_REDIRECTS);
    }

    private HttpResponse<InputStream> send(URI target, String etag, String lastModified) {
        HttpRequest.Builder request =
                HttpRequest.newBuilder(target).GET().timeout(policy.requestTimeout());
        // 조건부 요청. 뉴스원이 바뀐 것이 없으면 304로 답해 불필요한 전송을 줄인다(spec FR-013).
        if (etag != null && !etag.isBlank()) {
            request.header("If-None-Match", etag);
        }
        if (lastModified != null && !lastModified.isBlank()) {
            request.header("If-Modified-Since", lastModified);
        }
        try {
            return httpClient.send(request.build(), HttpResponse.BodyHandlers.ofInputStream());
        } catch (HttpTimeoutException e) {
            throw retryable(TIMEOUT);
        } catch (IOException e) {
            throw retryable(CONNECTION_FAILED);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw retryable(CONNECTION_FAILED);
        }
    }

    private NewsHttpResponse toResponse(HttpResponse<InputStream> response, int status) {
        if (status == 304) {
            closeQuietly(response.body());
            return new NewsHttpResponse(
                    304,
                    null,
                    new byte[0],
                    header(response, "etag"),
                    header(response, "last-modified"));
        }
        if (status >= 400) {
            closeQuietly(response.body());
            throw failureFor(status, retryAfter(response));
        }

        String contentType = header(response, "content-type");
        byte[] body = readBounded(response.body());
        return new NewsHttpResponse(
                status,
                contentType,
                body,
                header(response, "etag"),
                header(response, "last-modified"));
    }

    /**
     * 한도까지만 읽는다. 넘으면 그 자리에서 끊어 메모리를 지킨다.
     *
     * <p>한도 초과는 {@link NewsSourceClient.NewsSourceException}이라 아래 {@code catch (IOException)}에 걸리지
     * 않는다. try-with-resources는 본문에서 나간 예외를 primary로 두고 닫기에서 난 예외를 suppressed로 붙이므로(JLS 14.20.3.1),
     * 닫다가 실패해도 {@code RESPONSE_TOO_LARGE}가 {@code CONNECTION_FAILED}로 바뀌지 않는다. 분류가 뒤집히면 재시도 불가인 응답을
     * 일시 장애로 오인해 매 회차 다시 받으러 간다.
     *
     * <p>그래서 예외 유형을 바꾸지 않는다. {@code NewsSourceException}이 {@code IOException}을 상속하게 되거나 여기서 {@code
     * Exception}을 잡으면 그 보장이 깨진다.
     */
    private byte[] readBounded(InputStream in) {
        long limit = policy.maxResponseBytes();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        long total = 0;
        try (InputStream stream = in) {
            int read;
            while ((read = stream.read(buffer)) != -1) {
                total += read;
                if (total > limit) {
                    throw NewsHttpSecurityPolicy.reject(NewsHttpSecurityPolicy.RESPONSE_TOO_LARGE);
                }
                out.write(buffer, 0, read);
            }
        } catch (IOException e) {
            throw retryable(CONNECTION_FAILED);
        }
        return out.toByteArray();
    }

    private static boolean isRedirect(int status) {
        return status == 301 || status == 302 || status == 303 || status == 307 || status == 308;
    }

    private static String header(HttpResponse<?> response, String name) {
        return response.headers().firstValue(name).orElse(null);
    }

    private static void closeQuietly(InputStream in) {
        try {
            if (in != null) {
                in.close();
            }
        } catch (IOException ignored) {
            // 닫기 실패는 수집 결과에 영향을 주지 않는다.
        }
    }

    /**
     * 상태 코드를 재시도 분류로 옮긴다(spec FR-013, plan "수집과 재시도").
     *
     * <table>
     *   <tr><th>상태</th><th>분류</th><th>다시 부를까</th></tr>
     *   <tr><td>401·403</td><td>{@code UNAUTHORIZED}</td><td>아니오 — 등록 정보를 사람이 고쳐야 한다</td></tr>
     *   <tr><td>429</td><td>{@code RATE_LIMITED}</td><td>예 — 매체가 밝힌 간격을 실어 보낸다</td></tr>
     *   <tr><td>503</td><td>{@code SERVER_ERROR}</td><td>예 — 매체가 밝힌 간격을 실어 보낸다</td></tr>
     *   <tr><td>그 밖의 5xx</td><td>{@code SERVER_ERROR}</td><td>예 — 우리 백오프로</td></tr>
     *   <tr><td>그 밖의 4xx</td><td>{@code CLIENT_ERROR}</td><td>아니오 — 같은 요청은 같은 답을 받는다</td></tr>
     * </table>
     *
     * <p>{@code Retry-After}를 429·503에서만 싣는 것은 그 둘이 <b>매체가 언제 오라고 말하는</b> 상태이기 때문이다. 500이 준 {@code
     * Retry-After}는 뜻이 정해져 있지 않다.
     *
     * <p>분리해 둔 이유는 이 표가 규칙이라서다. {@code HttpResponse}를 만들지 않고도 시험이 표 전체를 고정할 수 있어야 한다.
     */
    static NewsSourceClient.NewsSourceException failureFor(
            int status, Optional<Duration> retryAfter) {
        if (status == 401 || status == 403) {
            return new NewsSourceClient.NewsSourceException(UNAUTHORIZED, false);
        }
        if (status == 429) {
            return retryable(RATE_LIMITED, retryAfter.orElse(null));
        }
        if (status == 503) {
            return retryable(SERVER_ERROR, retryAfter.orElse(null));
        }
        if (status >= 500) {
            return retryable(SERVER_ERROR);
        }
        return new NewsSourceClient.NewsSourceException(CLIENT_ERROR, false);
    }

    private static NewsSourceClient.NewsSourceException retryable(String category) {
        return new NewsSourceClient.NewsSourceException(category, true);
    }

    private static NewsSourceClient.NewsSourceException retryable(
            String category, Duration retryAfter) {
        return new NewsSourceClient.NewsSourceException(category, true, retryAfter);
    }

    /** 응답의 `Retry-After`를 초 단위로 읽는다. 없거나 해석할 수 없으면 빈 값이다(spec FR-013). */
    public static Optional<Duration> retryAfter(HttpResponse<?> response) {
        return response.headers()
                .firstValue("retry-after")
                .flatMap(
                        value -> {
                            try {
                                return Optional.of(
                                        Duration.ofSeconds(Long.parseLong(value.trim())));
                            } catch (NumberFormatException e) {
                                return Optional.empty();
                            }
                        });
    }
}
