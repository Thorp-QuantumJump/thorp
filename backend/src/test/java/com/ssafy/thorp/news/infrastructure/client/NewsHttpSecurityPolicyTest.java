package com.ssafy.thorp.news.infrastructure.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ssafy.thorp.news.application.port.NewsSourceClient;
import com.ssafy.thorp.news.infrastructure.client.NewsHttpSecurityPolicy.SourceKind;
import com.ssafy.thorp.news.infrastructure.config.NewsProperties;
import com.ssafy.thorp.news.infrastructure.config.NewsPublisherRegistry;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * 외부 뉴스원 호출의 공통 보안 경계(spec FR-020·FR-021).
 *
 * <p>네트워크를 쓰지 않는다. 검사 규칙만 단언한다. 실제 호출은 이 정책을 통과한 뒤에만 일어난다.
 */
class NewsHttpSecurityPolicyTest {

    private static final NewsPublisherRegistry REGISTRY =
            new NewsPublisherRegistry(
                    "news-publishers-test",
                    List.of(
                            new NewsPublisherRegistry.Publisher(
                                    "example-press",
                                    "예시경제",
                                    "ko",
                                    "example.co.kr",
                                    "https://rss.example.co.kr/economy.xml",
                                    true)));

    private static NewsHttpSecurityPolicy policy() {
        NewsProperties.Http http =
                new NewsProperties.Http(
                        3,
                        Duration.ofSeconds(5),
                        Duration.ofSeconds(20),
                        10_485_760L,
                        Set.of("application/rss+xml", "application/xml", "text/xml"),
                        Set.of("application/json"),
                        List.of("api.gdeltproject.org", "data.gdeltproject.org"));
        return new NewsHttpSecurityPolicy(http, REGISTRY);
    }

    @Nested
    @DisplayName("scheme와 host allowlist")
    class Allowlist {

        @Test
        @DisplayName("등록된 매체 도메인의 HTTPS 주소만 통과한다")
        void 매체_주소() {
            assertThat(
                            policy().requireAllowedUrl(
                                            "https://rss.example.co.kr/economy.xml",
                                            SourceKind.RSS))
                    .isEqualTo(URI.create("https://rss.example.co.kr/economy.xml"));
        }

        @Test
        @DisplayName("HTTP는 거부한다")
        void 비https_거부() {
            assertThatThrownBy(
                            () ->
                                    policy().requireAllowedUrl(
                                                    "http://rss.example.co.kr/economy.xml",
                                                    SourceKind.RSS))
                    .isInstanceOf(NewsSourceClient.NewsSourceException.class)
                    .hasMessage(NewsHttpSecurityPolicy.INSECURE_SCHEME);
        }

        @Test
        @DisplayName("등록되지 않은 host는 거부한다")
        void 미등록_host_거부() {
            assertThatThrownBy(
                            () ->
                                    policy().requireAllowedUrl(
                                                    "https://evil.com/feed.xml", SourceKind.RSS))
                    .isInstanceOf(NewsSourceClient.NewsSourceException.class)
                    .hasMessage(NewsHttpSecurityPolicy.HOST_NOT_ALLOWED);
        }

        @Test
        @DisplayName("GDELT는 매체 등록표가 아니라 전용 allowlist를 쓴다")
        void gdelt_host() {
            assertThat(
                            policy().requireAllowedUrl(
                                            "https://api.gdeltproject.org/api/v2/doc/doc",
                                            SourceKind.GDELT_DOC))
                    .hasHost("api.gdeltproject.org");

            assertThatThrownBy(
                            () ->
                                    policy().requireAllowedUrl(
                                                    "https://rss.example.co.kr/economy.xml",
                                                    SourceKind.GDELT_DOC))
                    .hasMessage(NewsHttpSecurityPolicy.HOST_NOT_ALLOWED);
        }

        @Test
        @DisplayName("해석할 수 없는 주소는 거부한다")
        void 잘못된_주소() {
            assertThatThrownBy(() -> policy().requireAllowedUrl("not a url", SourceKind.RSS))
                    .hasMessage(NewsHttpSecurityPolicy.MALFORMED_URL);
            assertThatThrownBy(() -> policy().requireAllowedUrl("/relative", SourceKind.RSS))
                    .hasMessage(NewsHttpSecurityPolicy.MALFORMED_URL);
        }

        @Test
        @DisplayName("오류 메시지에 주소를 담지 않는다")
        void 주소_비노출() {
            assertThatThrownBy(
                            () ->
                                    policy().requireAllowedUrl(
                                                    "https://evil.com/feed.xml?token=secret",
                                                    SourceKind.RSS))
                    .hasMessageNotContaining("evil.com")
                    .hasMessageNotContaining("secret");
        }
    }

    @Nested
    @DisplayName("redirect")
    class Redirects {

        private final URI current = URI.create("https://rss.example.co.kr/economy.xml");

        @Test
        @DisplayName("hop마다 scheme와 host를 다시 검사한다")
        void hop_재검사() {
            assertThat(
                            policy().requireAllowedRedirect(
                                            "https://example.co.kr/moved.xml",
                                            current,
                                            1,
                                            SourceKind.RSS))
                    .hasHost("example.co.kr");

            assertThatThrownBy(
                            () ->
                                    policy().requireAllowedRedirect(
                                                    "https://evil.com/moved.xml",
                                                    current,
                                                    1,
                                                    SourceKind.RSS))
                    .hasMessage(NewsHttpSecurityPolicy.HOST_NOT_ALLOWED);

            assertThatThrownBy(
                            () ->
                                    policy().requireAllowedRedirect(
                                                    "http://example.co.kr/moved.xml",
                                                    current,
                                                    1,
                                                    SourceKind.RSS))
                    .hasMessage(NewsHttpSecurityPolicy.INSECURE_SCHEME);
        }

        @Test
        @DisplayName("상대 경로 Location은 현재 주소를 기준으로 푼다")
        void 상대_경로() {
            assertThat(policy().requireAllowedRedirect("/moved.xml", current, 1, SourceKind.RSS))
                    .isEqualTo(URI.create("https://rss.example.co.kr/moved.xml"));
        }

        @Test
        @DisplayName("최대 3회를 넘기면 거부한다")
        void hop_한도() {
            assertThat(policy().maxRedirects()).isEqualTo(3);
            assertThatThrownBy(
                            () ->
                                    policy().requireAllowedRedirect(
                                                    "https://example.co.kr/x",
                                                    current,
                                                    4,
                                                    SourceKind.RSS))
                    .hasMessage(NewsHttpSecurityPolicy.TOO_MANY_REDIRECTS);
        }
    }

    @Nested
    @DisplayName("content-type과 크기·시간 한도")
    class Bounds {

        @Test
        @DisplayName("RSS는 XML 계열만 받는다")
        void rss_content_type() {
            policy().requireAllowedContentType(
                            "application/rss+xml; charset=UTF-8", SourceKind.RSS);
            policy().requireAllowedContentType("text/xml", SourceKind.RSS);

            assertThatThrownBy(
                            () -> policy().requireAllowedContentType("text/html", SourceKind.RSS))
                    .hasMessage(NewsHttpSecurityPolicy.UNSUPPORTED_CONTENT_TYPE);
        }

        @Test
        @DisplayName("GDELT DOC은 JSON만 받는다")
        void gdelt_content_type() {
            policy().requireAllowedContentType("application/json", SourceKind.GDELT_DOC);

            assertThatThrownBy(
                            () ->
                                    policy().requireAllowedContentType(
                                                    "text/xml", SourceKind.GDELT_DOC))
                    .hasMessage(NewsHttpSecurityPolicy.UNSUPPORTED_CONTENT_TYPE);
        }

        @Test
        @DisplayName("content-type이 없으면 거부한다")
        void content_type_없음() {
            assertThatThrownBy(() -> policy().requireAllowedContentType(null, SourceKind.RSS))
                    .hasMessage(NewsHttpSecurityPolicy.UNSUPPORTED_CONTENT_TYPE);
        }

        @Test
        @DisplayName("크기와 시간 한도는 plan이 고정한 값이다")
        void 한도값() {
            NewsHttpSecurityPolicy policy = policy();

            assertThat(policy.maxResponseBytes()).isEqualTo(10L * 1024 * 1024);
            assertThat(policy.connectTimeout()).isEqualTo(Duration.ofSeconds(5));
            assertThat(policy.requestTimeout()).isEqualTo(Duration.ofSeconds(20));
        }
    }

    @Nested
    @DisplayName("XML 파서")
    class XmlParser {

        @Test
        @DisplayName("DTD와 외부 엔티티를 비활성화한다")
        void 외부_엔티티_차단() {
            javax.xml.stream.XMLInputFactory factory = policy().secureXmlInputFactory();

            assertThat(factory.getProperty(javax.xml.stream.XMLInputFactory.SUPPORT_DTD))
                    .isEqualTo(Boolean.FALSE);
            assertThat(
                            factory.getProperty(
                                    javax.xml.stream.XMLInputFactory
                                            .IS_SUPPORTING_EXTERNAL_ENTITIES))
                    .isEqualTo(Boolean.FALSE);
        }
    }
}
