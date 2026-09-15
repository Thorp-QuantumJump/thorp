package com.ssafy.thorp.news.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

/** 뉴스 설정과 버전 관리되는 등록표의 검증 규칙. 근거: specs/012-market-news-digest/spec.md, plan.md. */
class NewsConfigTest {

    private static NewsProperties.Http defaultHttp() {
        return new NewsProperties.Http(
                3,
                Duration.ofSeconds(5),
                Duration.ofSeconds(20),
                10_485_760L,
                Set.of("application/xml"),
                Set.of("application/json"),
                List.of("api.gdeltproject.org"));
    }

    private static NewsProperties properties(com.ssafy.thorp.news.domain.vo.NewsExposureMode mode) {
        return new NewsProperties(
                mode,
                Duration.ofMinutes(30),
                Duration.ofMinutes(15),
                Duration.ofMinutes(15),
                LocalDate.of(2026, 11, 7),
                "Asia/Seoul",
                93,
                20,
                3,
                Duration.ofHours(48),
                defaultHttp());
    }

    @Nested
    @DisplayName("HTTP 보안 경계 (spec FR-020)")
    class HttpBounds {

        @Test
        @DisplayName("기본 한도는 plan이 고정한 값이다")
        void 기본_한도() {
            NewsProperties.Http http = defaultHttp();

            assertThat(http.maxRedirects()).isEqualTo(3);
            assertThat(http.connectTimeout()).isEqualTo(Duration.ofSeconds(5));
            assertThat(http.requestTimeout()).isEqualTo(Duration.ofSeconds(20));
            assertThat(http.maxResponseBytes()).isEqualTo(10L * 1024 * 1024);
        }

        @Test
        @DisplayName("redirect 한도를 완화할 수 없다")
        void redirect_완화_거부() {
            assertThatThrownBy(
                            () ->
                                    new NewsProperties.Http(
                                            4,
                                            Duration.ofSeconds(5),
                                            Duration.ofSeconds(20),
                                            10_485_760L,
                                            Set.of("application/xml"),
                                            Set.of("application/json"),
                                            List.of("api.gdeltproject.org")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("maxRedirects");
        }

        @Test
        @DisplayName("요청 제한 시간을 완화할 수 없다")
        void 시간_완화_거부() {
            assertThatThrownBy(
                            () ->
                                    new NewsProperties.Http(
                                            3,
                                            Duration.ofSeconds(5),
                                            Duration.ofSeconds(21),
                                            10_485_760L,
                                            Set.of("application/xml"),
                                            Set.of("application/json"),
                                            List.of("api.gdeltproject.org")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("requestTimeout");
        }

        @Test
        @DisplayName("응답 크기 상한을 완화할 수 없다")
        void 크기_완화_거부() {
            assertThatThrownBy(
                            () ->
                                    new NewsProperties.Http(
                                            3,
                                            Duration.ofSeconds(5),
                                            Duration.ofSeconds(20),
                                            10_485_761L,
                                            Set.of("application/xml"),
                                            Set.of("application/json"),
                                            List.of("api.gdeltproject.org")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("maxResponseBytes");
        }
    }

    @Nested
    @DisplayName("노출·보관 설정")
    class Exposure {

        @Test
        @DisplayName("노출 모드는 서버 설정이 정한다 (spec FR-016)")
        void 노출_모드() {
            assertThat(
                            properties(com.ssafy.thorp.news.domain.vo.NewsExposureMode.INTERNAL)
                                    .isPublic())
                    .isFalse();
            assertThat(
                            properties(com.ssafy.thorp.news.domain.vo.NewsExposureMode.PUBLIC)
                                    .isPublic())
                    .isTrue();
        }

        @Test
        @DisplayName("삭제 기준일은 시간대와 함께 해석한다 (spec FR-019)")
        void 삭제_기준일() {
            NewsProperties properties =
                    properties(com.ssafy.thorp.news.domain.vo.NewsExposureMode.INTERNAL);

            assertThat(properties.retentionDate()).isEqualTo(LocalDate.of(2026, 11, 7));
            assertThat(properties.retentionZone()).isEqualTo("Asia/Seoul");
        }

        @Test
        @DisplayName("과거 조회 상한과 묶음 수 상한은 서로 다른 경계다")
        void 상한_분리() {
            NewsProperties properties =
                    properties(com.ssafy.thorp.news.domain.vo.NewsExposureMode.INTERNAL);

            assertThat(properties.maxHistoricalRangeDays()).isEqualTo(93);
            assertThat(properties.maxDigests()).isEqualTo(3);
        }
    }

    @Nested
    @DisplayName("매체 등록표 (spec FR-009)")
    class PublisherRegistry {

        @Test
        @DisplayName("실제 파일을 읽고 매체 항목은 비어 있어도 정상이다")
        void 파일_로딩() {
            NewsPublisherRegistry registry =
                    NewsConfig.read(
                            new ClassPathResource(NewsConfig.PUBLISHER_REGISTRY_PATH),
                            NewsPublisherRegistry.class);

            assertThat(registry.version()).isEqualTo("news-publishers-v1");
            assertThat(registry.publishers()).isEmpty();
            assertThat(registry.enabledPublishers()).isEmpty();
        }

        @Test
        @DisplayName("host가 등록 도메인과 일치하는 HTTPS 채널만 공식 채널이다")
        void 공식_채널_판정() {
            NewsPublisherRegistry.Publisher publisher =
                    new NewsPublisherRegistry.Publisher(
                            "example-press",
                            "예시경제",
                            "ko",
                            "example.co.kr",
                            "https://rss.example.co.kr/economy.xml",
                            true);

            assertThat(publisher.registeredDomain()).isEqualTo("example.co.kr");
        }

        @Test
        @DisplayName("HTTPS가 아닌 채널은 등록을 거부한다")
        void 비https_거부() {
            assertThatThrownBy(
                            () ->
                                    new NewsPublisherRegistry.Publisher(
                                            "example-press",
                                            "예시경제",
                                            "ko",
                                            "example.co.kr",
                                            "http://rss.example.co.kr/economy.xml",
                                            true))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("HTTPS");
        }

        @Test
        @DisplayName("host가 등록 도메인과 다른 집계 feed는 등록을 거부한다")
        void 집계_feed_거부() {
            assertThatThrownBy(
                            () ->
                                    new NewsPublisherRegistry.Publisher(
                                            "example-press",
                                            "예시경제",
                                            "ko",
                                            "example.co.kr",
                                            "https://aggregator.example.com/feed.xml",
                                            true))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("공식 채널이 아니다");
        }

        @Test
        @DisplayName("등록된 도메인의 host만 allowlist를 통과한다")
        void host_allowlist() {
            NewsPublisherRegistry registry =
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

            assertThat(registry.findByHost("rss.example.co.kr")).isPresent();
            assertThat(registry.findByHost("example.co.kr")).isPresent();
            assertThat(registry.findByHost("evil.com")).isEmpty();
            assertThat(registry.findByHost("notexample.co.kr")).isEmpty();
        }
    }

    @Nested
    @DisplayName("섹터-주제 등록표 (spec FR-005·FR-010)")
    class LinkRegistry {

        private NewsLinkRegistry testRegistry() {
            return new NewsLinkRegistry(
                    "news-link-test",
                    List.of(
                            new NewsLinkRegistry.Topic("monetary-policy", "통화정책", true, List.of()),
                            new NewsLinkRegistry.Topic("prices", "물가", false, List.of())),
                    List.of(new NewsLinkRegistry.CategoryMapping("경제", "monetary-policy")));
        }

        @Test
        @DisplayName("실제 파일을 읽고 섹터 식별자를 스스로 만들지 않는다")
        void 파일_로딩() {
            NewsLinkRegistry registry =
                    NewsConfig.read(
                            new ClassPathResource(NewsConfig.LINK_REGISTRY_PATH),
                            NewsLinkRegistry.class);

            assertThat(registry.version()).isEqualTo("news-link-v1");
            assertThat(registry.topics()).isNotEmpty();
            assertThat(registry.knownSectorIds())
                    .as("001의 대응표가 확정되기 전에는 어떤 섹터 식별자도 선언하지 않는다")
                    .isEmpty();
        }

        @Test
        @DisplayName("뉴스원 분류 값은 매핑을 통과한 경우에만 주제가 된다")
        void 분류값_매핑() {
            NewsLinkRegistry registry = testRegistry();

            assertThat(registry.resolveTopicKey("경제")).contains("monetary-policy");
            assertThat(registry.resolveTopicKey(" 경제 ")).contains("monetary-policy");
            assertThat(registry.resolveTopicKey("연예")).as("매핑되지 않은 분류 값은 연결 근거가 아니다").isEmpty();
            assertThat(registry.resolveTopicKey(null)).isEmpty();
        }

        @Test
        @DisplayName("등록되지 않은 주제로 매핑하면 등록표를 거부한다")
        void 미등록_주제_매핑_거부() {
            List<NewsLinkRegistry.Topic> topics =
                    List.of(new NewsLinkRegistry.Topic("prices", "물가", true, List.of()));
            List<NewsLinkRegistry.CategoryMapping> mappings =
                    List.of(new NewsLinkRegistry.CategoryMapping("경제", "unknown-topic"));

            assertThatThrownBy(() -> new NewsLinkRegistry("news-link-test", topics, mappings))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("등록되지 않은 topicKey");
        }

        @Test
        @DisplayName("해외 보도를 받을 수 있는 국내 연결 주제를 구분한다")
        void 국내_연결_주제() {
            NewsLinkRegistry registry = testRegistry();

            assertThat(registry.domesticLinkTopicKeys()).containsExactly("monetary-policy");
        }
    }
}
