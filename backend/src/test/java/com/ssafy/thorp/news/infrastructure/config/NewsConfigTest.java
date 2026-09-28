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
        @DisplayName("실제 등록표 파일의 항목이 모두 등록 조건을 통과한다")
        void 파일_로딩() {
            NewsPublisherRegistry registry =
                    NewsConfig.read(
                            new ClassPathResource(NewsConfig.PUBLISHER_REGISTRY_PATH),
                            NewsPublisherRegistry.class);

            assertThat(registry.version()).isEqualTo("news-publishers-v1");
            assertThat(registry.publishers())
                    .as("항목이 비어 있으면 수집할 채널이 없어 화면이 영영 '준비 중'이다")
                    .isNotEmpty()
                    .allSatisfy(
                            publisher -> {
                                assertThat(publisher.rssUrl()).startsWith("https://");
                                assertThat(java.net.URI.create(publisher.rssUrl()).getHost())
                                        .as("집계·재배포 feed를 공식 채널로 등록하지 않는다(spec FR-009)")
                                        .endsWith(publisher.registeredDomain());
                            });
        }

        @Test
        @DisplayName("등록한 매체의 분류 값이 연결 등록표에 매핑돼 있다")
        void 분류_매핑_존재() {
            NewsLinkRegistry links =
                    NewsConfig.read(
                            new ClassPathResource(NewsConfig.LINK_REGISTRY_PATH),
                            NewsLinkRegistry.class);

            // 경향신문 경제 피드가 실제로 주는 분류 값이다. 하나라도 빠지면 그 분류의 기사가 통째로
            // 제외되고, 제외는 조용해서 화면이 비어 보이는 것으로만 드러난다(spec FR-010).
            //
            // 저장된 문자열을 직접 비교하지 않고 조회를 부른다. 등록표가 대소문자를 접어 두므로
            // 문자열 비교는 실제 동작과 어긋난다.
            assertThat(
                            List.of(
                                            "경제 일반",
                                            "금융·재테크",
                                            "산업·통상",
                                            "기업소식",
                                            "생활경제·소비자",
                                            "IT비즈·정책",
                                            "PC·기기가전")
                                    .stream()
                                    .filter(category -> links.resolveTopicKey(category).isEmpty())
                                    .toList())
                    .as("매핑되지 않은 분류 값")
                    .isEmpty();

            assertThat(links.resolveTopicKey("부동산"))
                    .as("매핑하지 않기로 한 값은 그대로 두고 미등록 건수로만 센다")
                    .isEmpty();

            // 매일경제가 실제로 주는 값.
            assertThat(links.resolveTopicKey("증권")).contains("markets");
            assertThat(links.resolveTopicKey("기업/경영")).contains("industry");
        }

        @Test
        @DisplayName("채널 주제를 선언한 매체는 그 주제가 등록표에 있다")
        void 채널_주제_존재() {
            NewsPublisherRegistry registry =
                    NewsConfig.read(
                            new ClassPathResource(NewsConfig.PUBLISHER_REGISTRY_PATH),
                            NewsPublisherRegistry.class);
            NewsLinkRegistry links =
                    NewsConfig.read(
                            new ClassPathResource(NewsConfig.LINK_REGISTRY_PATH),
                            NewsLinkRegistry.class);

            // 등록표에 없는 주제를 적으면 보완 경로가 조용히 아무 일도 하지 않고, 그 채널의 기사가
            // 전량 제외된다. 제외는 로그에 건수로만 남아 눈에 띄지 않는다.
            assertThat(
                            registry.publishers().stream()
                                    .flatMap(publisher -> publisher.topicKeys().stream())
                                    .filter(topicKey -> links.findTopic(topicKey).isEmpty())
                                    .toList())
                    .as("등록표에 없는 채널 주제")
                    .isEmpty();
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

            assertThat(registry.version()).isEqualTo("news-link-v2");
            assertThat(registry.topics()).isNotEmpty();
            // 투자 대상 섹터는 001 대응표(data/config/sector-map-2026-09.json)의 8개를 그대로 가리키고,
            // 나머지는 012가 소유하는 뉴스 전용 섹터다(Session 2026-09-25).
            assertThat(registry.newsSectors())
                    .filteredOn(s -> s.owner() == NewsLinkRegistry.SectorOwner.SECTOR_MAP)
                    .extracting(NewsLinkRegistry.NewsSector::sectorId)
                    .containsExactly(
                            "SEMICONDUCTOR",
                            "SECONDARY_BATTERY",
                            "BANK",
                            "SECURITIES",
                            "COSMETICS",
                            "CONSTRUCTION",
                            "AUTO",
                            "HEAVY_INDUSTRY");
            assertThat(registry.newsOwnedSectorIds())
                    .as("뉴스 전용 섹터는 NEWS_로 시작해 001 식별자와 겹치지 않는다")
                    .isNotEmpty()
                    .allSatisfy(
                            id -> assertThat(id).startsWith(NewsLinkRegistry.NEWS_SECTOR_PREFIX));
            assertThat(registry.knownSectorIds())
                    .as("모든 연결은 칩 목록에 있는 섹터로 간다")
                    .isSubsetOf(
                            registry.newsSectors().stream()
                                    .map(NewsLinkRegistry.NewsSector::sectorId)
                                    .toList());
            assertThat(registry.topics())
                    .filteredOn(topic -> !topic.titleKeywords().isEmpty())
                    .as("제목 키워드 주제는 해외 기사를 받는 근거가 되지 않는다")
                    .allSatisfy(topic -> assertThat(topic.domesticLink()).isFalse());
        }

        @Test
        @DisplayName("제목 키워드는 제외어를 먼저 지우고 대소문자를 가리지 않고 찾는다")
        void 제목_키워드() {
            NewsLinkRegistry registry =
                    new NewsLinkRegistry(
                            "news-link-test",
                            List.of(
                                    new NewsLinkRegistry.Topic(
                                            "sector-bank",
                                            "은행 업종",
                                            false,
                                            List.of("BANK"),
                                            List.of("은행권", "은행"),
                                            List.of("한국은행")),
                                    new NewsLinkRegistry.Topic(
                                            "sector-semiconductor",
                                            "반도체 업종",
                                            false,
                                            List.of("SEMICONDUCTOR"),
                                            List.of("HBM"),
                                            List.of())),
                            List.of());

            assertThat(registry.matchTitle("한국은행, 기준금리 동결")).isEmpty();
            assertThat(registry.matchTitle("은행권 대출 금리 인하"))
                    .containsExactly(java.util.Map.entry("sector-bank", "은행권"));
            assertThat(registry.matchTitle("삼성, hbm 공급 확대"))
                    .containsExactly(java.util.Map.entry("sector-semiconductor", "hbm"));
            assertThat(registry.matchTitle(null)).isEmpty();
        }

        @Test
        @DisplayName("영문 키워드는 영문 낱말 경계에서만 걸린다")
        void 영문_키워드_경계() {
            NewsLinkRegistry.Topic topic =
                    new NewsLinkRegistry.Topic(
                            "sector-news-it",
                            "IT·AI",
                            false,
                            List.of("NEWS_IT"),
                            List.of("AI"),
                            List.of());

            assertThat(topic.matchTitle("삼성, AI 칩 투자")).contains("ai");
            assertThat(topic.matchTitle("AI로 보고서 뚝딱")).contains("ai");
            assertThat(topic.matchTitle("Taiwan 반도체 수출")).isEmpty();
        }

        @Test
        @DisplayName("뉴스 전용 섹터는 NEWS_로 시작해야 하고, 칩 목록에 없는 섹터로 연결할 수 없다")
        void 뉴스_섹터_규칙() {
            org.assertj.core.api.Assertions.assertThatThrownBy(
                            () ->
                                    new NewsLinkRegistry.NewsSector(
                                            "IT", "IT", NewsLinkRegistry.SectorOwner.NEWS))
                    .isInstanceOf(IllegalArgumentException.class);
            org.assertj.core.api.Assertions.assertThatThrownBy(
                            () ->
                                    new NewsLinkRegistry(
                                            "news-link-test",
                                            List.of(
                                                    new NewsLinkRegistry.NewsSector(
                                                            "NEWS_IT",
                                                            "IT·AI",
                                                            NewsLinkRegistry.SectorOwner.NEWS)),
                                            List.of(
                                                    new NewsLinkRegistry.Topic(
                                                            "sector-bank",
                                                            "은행 업종",
                                                            false,
                                                            List.of("BANK"),
                                                            List.of("은행권"),
                                                            List.of())),
                                            List.of()))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("제목 키워드가 있는데 섹터가 없으면 등록표를 받지 않는다")
        void 제목_키워드_섹터_필수() {
            org.assertj.core.api.Assertions.assertThatThrownBy(
                            () ->
                                    new NewsLinkRegistry.Topic(
                                            "sector-x",
                                            "업종",
                                            false,
                                            List.of(),
                                            List.of("키워드"),
                                            List.of()))
                    .isInstanceOf(IllegalArgumentException.class);
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
