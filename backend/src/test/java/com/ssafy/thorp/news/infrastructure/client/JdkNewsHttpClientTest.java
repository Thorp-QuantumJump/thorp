package com.ssafy.thorp.news.infrastructure.client;

import static org.assertj.core.api.Assertions.assertThat;

import com.ssafy.thorp.news.application.port.NewsSourceClient;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.net.ssl.SSLSession;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * 뉴스원이 준 상태 코드를 어떻게 읽는지(spec FR-013, plan "수집과 재시도").
 *
 * <p><b>왜 이 시험이 필요한가.</b> 분류가 틀리면 드러나는 방식이 고약하다. 401을 일시 실패로 보면 고칠 수 없는 요청을 회차마다 세 번씩 두드리고, 429를 설정
 * 오류로 보면 잠깐 밀린 매체가 영영 비활성화 후보로 남는다. 둘 다 조용히 일어나고 로그에는 분류 코드만 남는다.
 *
 * <p>실제 HTTP를 띄우지 않는다. 보안 경계가 HTTPS만 허용해서 로컬 평문 서버로는 여기까지 오지도 못하고, 자체 서명 인증서를 세우는 비용은 이 표를 확인하는
 * 값어치보다 크다. 표 자체를 {@code failureFor}로 떼어 두고 그것을 본다.
 */
class JdkNewsHttpClientTest {

    @Nested
    @DisplayName("상태 코드 분류")
    class Classification {

        @Test
        @DisplayName("401·403은 다시 시도하지 않는다")
        void 설정_오류() {
            for (int status : new int[] {401, 403}) {
                NewsSourceClient.NewsSourceException failure =
                        JdkNewsHttpClient.failureFor(status, Optional.empty());

                assertThat(failure.errorCategory()).isEqualTo(JdkNewsHttpClient.UNAUTHORIZED);
                assertThat(failure.isRetryable())
                        .as("등록 정보를 사람이 고치기 전에는 같은 답이 온다. 회차마다 세 번씩 두드릴 이유가 없다")
                        .isFalse();
            }
        }

        @Test
        @DisplayName("429는 일시 실패이고 매체가 밝힌 간격을 싣는다")
        void 호출_제한() {
            NewsSourceClient.NewsSourceException failure =
                    JdkNewsHttpClient.failureFor(429, Optional.of(Duration.ofSeconds(12)));

            assertThat(failure.errorCategory()).isEqualTo(JdkNewsHttpClient.RATE_LIMITED);
            assertThat(failure.isRetryable()).isTrue();
            assertThat(failure.retryAfter())
                    .as("매체가 정한 간격을 우리 백오프로 덮으면 차단당한다")
                    .contains(Duration.ofSeconds(12));
        }

        @Test
        @DisplayName("503도 매체가 밝힌 간격을 싣는다")
        void 일시_중단() {
            NewsSourceClient.NewsSourceException failure =
                    JdkNewsHttpClient.failureFor(503, Optional.of(Duration.ofSeconds(30)));

            assertThat(failure.errorCategory()).isEqualTo(JdkNewsHttpClient.SERVER_ERROR);
            assertThat(failure.isRetryable()).isTrue();
            assertThat(failure.retryAfter()).contains(Duration.ofSeconds(30));
        }

        @Test
        @DisplayName("그 밖의 5xx는 일시 실패지만 간격을 싣지 않는다")
        void 서버_오류() {
            NewsSourceClient.NewsSourceException failure =
                    JdkNewsHttpClient.failureFor(500, Optional.of(Duration.ofSeconds(30)));

            assertThat(failure.errorCategory()).isEqualTo(JdkNewsHttpClient.SERVER_ERROR);
            assertThat(failure.isRetryable()).isTrue();
            assertThat(failure.retryAfter())
                    .as("500이 준 Retry-After는 뜻이 정해져 있지 않다. 우리 백오프를 쓴다")
                    .isEmpty();
        }

        @Test
        @DisplayName("그 밖의 4xx는 다시 시도하지 않는다")
        void 요청_오류() {
            NewsSourceClient.NewsSourceException failure =
                    JdkNewsHttpClient.failureFor(404, Optional.empty());

            assertThat(failure.errorCategory()).isEqualTo(JdkNewsHttpClient.CLIENT_ERROR);
            assertThat(failure.isRetryable()).isFalse();
        }

        @Test
        @DisplayName("분류 코드에 주소나 상태 숫자를 담지 않는다")
        void 분류_코드만() {
            NewsSourceClient.NewsSourceException failure =
                    JdkNewsHttpClient.failureFor(429, Optional.of(Duration.ofSeconds(12)));

            assertThat(failure.errorCategory())
                    .as("이 값이 그대로 저장되고 지표 label이 된다(spec FR-021)")
                    .matches("^[A-Z_]+$");
        }
    }

    @Nested
    @DisplayName("Retry-After 읽기")
    class RetryAfterHeader {

        @Test
        @DisplayName("초 단위 숫자를 읽는다")
        void 숫자() {
            assertThat(JdkNewsHttpClient.retryAfter(responseWith("retry-after", "120")))
                    .contains(Duration.ofSeconds(120));
        }

        @Test
        @DisplayName("헤더가 없으면 빈 값이다")
        void 없음() {
            assertThat(JdkNewsHttpClient.retryAfter(responseWithoutHeaders())).isEmpty();
        }

        @Test
        @DisplayName("해석할 수 없는 값은 빈 값으로 둔다")
        void 해석_불가() {
            // HTTP 날짜 형식도 규약에는 있지만 우리는 읽지 않는다. 읽지 못한 것을 0으로 바꾸면
            // "지금 바로 다시 오라"가 되어 방금 밀어낸 매체를 곧장 다시 두드린다.
            assertThat(
                            JdkNewsHttpClient.retryAfter(
                                    responseWith("retry-after", "Wed, 21 Oct 2026 07:28:00 GMT")))
                    .isEmpty();
        }

        @Test
        @DisplayName("long 범위를 넘는 값은 빈 값으로 둔다")
        void 범위_초과() {
            assertThat(
                            JdkNewsHttpClient.retryAfter(
                                    responseWith("retry-after", "99999999999999999999")))
                    .as("해석하지 못한 것을 억지로 채우면 그 값이 어디까지 번지는지 알 수 없다")
                    .isEmpty();
        }

        @Test
        @DisplayName("음수도 읽은 그대로 넘긴다")
        void 음수() {
            // 여기서 0으로 접지 않는다. 뉴스원이 무엇을 줬는지는 그대로 넘기고, 그 값을 안전한
            // 범위로 자르는 일은 재시도 규칙 한 곳에서만 한다. 두 곳에서 자르면 어느 쪽이 잘랐는지
            // 나중에 알 수 없다.
            assertThat(JdkNewsHttpClient.retryAfter(responseWith("retry-after", "-5")))
                    .contains(Duration.ofSeconds(-5));
        }

        @Test
        @DisplayName("앞뒤 공백은 무시한다")
        void 공백() {
            assertThat(JdkNewsHttpClient.retryAfter(responseWith("retry-after", "  45 ")))
                    .contains(Duration.ofSeconds(45));
        }
    }

    private static HttpResponse<Void> responseWithoutHeaders() {
        return new HeadersOnlyResponse(HttpHeaders.of(Map.of(), (k, v) -> true));
    }

    private static HttpResponse<Void> responseWith(String name, String value) {
        return new HeadersOnlyResponse(
                HttpHeaders.of(Map.of(name, List.of(value)), (k, v) -> true));
    }

    /**
     * 헤더만 들고 있는 응답 대역.
     *
     * <p>{@code HttpResponse}는 인터페이스라 헤더 하나를 보려고 전체를 구현해야 한다. 나머지는 이 시험이 부르지 않으므로 부르는 순간 터지게 둔다 —
     * 조용히 기본값을 돌려주면 시험이 다른 길을 타고도 통과한다.
     */
    private record HeadersOnlyResponse(HttpHeaders headers) implements HttpResponse<Void> {

        @Override
        public int statusCode() {
            throw new UnsupportedOperationException();
        }

        @Override
        public HttpRequest request() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<HttpResponse<Void>> previousResponse() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Void body() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<SSLSession> sslSession() {
            throw new UnsupportedOperationException();
        }

        @Override
        public URI uri() {
            throw new UnsupportedOperationException();
        }

        @Override
        public HttpClient.Version version() {
            throw new UnsupportedOperationException();
        }
    }
}
