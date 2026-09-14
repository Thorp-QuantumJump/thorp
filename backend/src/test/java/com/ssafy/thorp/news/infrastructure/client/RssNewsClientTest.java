package com.ssafy.thorp.news.infrastructure.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ssafy.thorp.news.application.port.NewsSourceClient;
import com.ssafy.thorp.news.application.port.RssNewsSource;
import com.ssafy.thorp.news.application.result.CollectedNewsItem;
import com.ssafy.thorp.news.application.result.NewsFetchResult;
import com.ssafy.thorp.news.infrastructure.client.NewsHttpSecurityPolicy.SourceKind;
import com.ssafy.thorp.news.infrastructure.config.NewsProperties;
import com.ssafy.thorp.news.infrastructure.config.NewsPublisherRegistry;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * RSS 2.0 메타데이터 수집(spec FR-009·FR-012·FR-013).
 *
 * <p>네트워크를 쓰지 않는다. {@link NewsHttpClient}를 대역으로 바꿔 고정 fixture를 먹인다.
 *
 * <p>핵심은 셋이다. 본문에 해당하는 값을 읽지 않는다. 발행 시각을 지어내지 않는다. 변경 없음 응답이 수집 위치를 건드리지 않는다.
 */
class RssNewsClientTest {

    private static final Instant COLLECTED = Instant.parse("2026-09-10T05:00:00Z");

    private static final RssNewsSource SOURCE =
            new RssNewsSource(
                    1L, "example-press", "https://rss.example.co.kr/economy.xml", null, null);

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
        return new NewsHttpSecurityPolicy(
                new NewsProperties.Http(
                        3,
                        Duration.ofSeconds(5),
                        Duration.ofSeconds(20),
                        10_485_760L,
                        Set.of("application/rss+xml", "application/xml", "text/xml"),
                        Set.of("application/json"),
                        List.of("api.gdeltproject.org")),
                REGISTRY);
    }

    private static byte[] fixture(String name) {
        try (InputStream in = RssNewsClientTest.class.getResourceAsStream("/news/rss/" + name)) {
            if (in == null) {
                throw new IllegalStateException("fixture를 찾을 수 없다: " + name);
            }
            return in.readAllBytes();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** 고정 응답을 돌려주는 대역. 마지막으로 받은 조건부 요청 헤더를 기억한다. */
    private static final class StubHttpClient implements NewsHttpClient {

        private final NewsHttpResponse response;
        private String seenEtag;
        private String seenLastModified;

        private StubHttpClient(NewsHttpResponse response) {
            this.response = response;
        }

        static StubHttpClient ok(String fixtureName) {
            return new StubHttpClient(
                    new NewsHttpResponse(
                            200,
                            "application/rss+xml; charset=UTF-8",
                            fixture(fixtureName),
                            "\"etag-1\"",
                            "Thu, 10 Sep 2026 04:00:00 GMT"));
        }

        static StubHttpClient notModified() {
            return new StubHttpClient(
                    new NewsHttpResponse(304, null, new byte[0], "\"etag-1\"", null));
        }

        @Override
        public NewsHttpResponse get(URI url, SourceKind kind, String etag, String lastModified) {
            this.seenEtag = etag;
            this.seenLastModified = lastModified;
            return response;
        }
    }

    private static RssNewsClient client(NewsHttpClient http) {
        return new RssNewsClient(
                http, policy(), java.time.Clock.fixed(COLLECTED, java.time.ZoneOffset.UTC));
    }

    @Nested
    @DisplayName("본문을 읽지 않는다 (spec FR-002)")
    class NoBody {

        @Test
        @DisplayName("description이 있어도 수집 결과 어디에도 담기지 않는다")
        void description_무시() {
            NewsFetchResult result = client(StubHttpClient.ok("valid.xml")).fetch(SOURCE);

            assertThat(result.items()).hasSize(2);
            assertThat(result.items())
                    .allSatisfy(
                            item ->
                                    assertThat(item.toString())
                                            .doesNotContain("본문 조각")
                                            .doesNotContain("채널 설명"));
        }

        @Test
        @DisplayName("수집 결과 record에 본문에 해당하는 필드가 없다")
        void 본문_필드_없음() {
            List<String> suspicious =
                    java.util.Arrays.stream(CollectedNewsItem.class.getRecordComponents())
                            .map(java.lang.reflect.RecordComponent::getName)
                            .map(String::toLowerCase)
                            .filter(
                                    n ->
                                            n.contains("body")
                                                    || n.contains("content")
                                                    || n.contains("description")
                                                    || n.contains("html"))
                            .toList();

            assertThat(suspicious).isEmpty();
        }
    }

    @Nested
    @DisplayName("필수·선택 필드")
    class Fields {

        @Test
        @DisplayName("제목·링크·발행 시각·guid·분류를 읽는다")
        void 기본_필드() {
            NewsFetchResult result = client(StubHttpClient.ok("valid.xml")).fetch(SOURCE);

            CollectedNewsItem first = result.items().get(0);
            assertThat(first.title()).isEqualTo("반도체 수출 지표가 발표됐다");
            assertThat(first.url()).isEqualTo("https://rss.example.co.kr/economy/1001");
            assertThat(first.sourceItemId()).isEqualTo("example-1001");
            assertThat(first.sourceCategories()).containsExactly("경제");
            assertThat(first.parsedPublishedAt()).isEqualTo(Instant.parse("2026-09-10T04:00:00Z"));
            assertThat(first.recordedAt()).as("RSS에는 GDELT 기록 시각이 없다").isNull();
        }

        @Test
        @DisplayName("선택 필드가 없어도 항목을 버리지 않는다")
        void 선택_필드_누락() {
            NewsFetchResult result = client(StubHttpClient.ok("optional-fields.xml")).fetch(SOURCE);

            assertThat(result.items()).hasSize(2);
            assertThat(result.items().get(0).parsedPublishedAt()).isNull();
            assertThat(result.items().get(1).url()).isNull();
            assertThat(result.items().get(1).sourceItemId()).isEqualTo("example-2002");
        }

        @Test
        @DisplayName("제목이 없는 항목은 그 항목만 제외한다")
        void 제목_없는_항목_제외() {
            NewsFetchResult result = client(StubHttpClient.ok("optional-fields.xml")).fetch(SOURCE);

            assertThat(result.items())
                    .as("fixture의 셋째 항목은 제목이 없다")
                    .extracting(CollectedNewsItem::title)
                    .doesNotContainNull();
        }

        @Test
        @DisplayName("guid는 URL로 가정하지 않는 불투명한 값이다")
        void 불투명한_guid() {
            NewsFetchResult result = client(StubHttpClient.ok("duplicate-guid.xml")).fetch(SOURCE);

            assertThat(result.items().get(0).sourceItemId())
                    .as("다른 매체가 같은 guid를 써도 그대로 읽는다. 구분은 매체 식별자가 한다")
                    .isEqualTo("example-1001");
        }
    }

    @Nested
    @DisplayName("발행 시각 (spec FR-012)")
    class PublicationTime {

        @Test
        @DisplayName("timezone이 있는 RFC 822 시각만 파싱한다")
        void 시각_파싱() {
            NewsFetchResult result = client(StubHttpClient.ok("dates.xml")).fetch(SOURCE);

            assertThat(result.items()).hasSize(5);
            assertThat(result.items().get(0).parsedPublishedAt())
                    .as("두 자리 연도 + 오프셋")
                    .isEqualTo(Instant.parse("2026-09-10T04:00:00Z"));
            assertThat(result.items().get(1).parsedPublishedAt())
                    .as("timezone이 없으면 시각을 지어내지 않는다")
                    .isNull();
            assertThat(result.items().get(2).parsedPublishedAt()).as("해석할 수 없으면 비운다").isNull();
            assertThat(result.items().get(4).parsedPublishedAt())
                    .as("GMT 표기")
                    .isEqualTo(Instant.parse("2026-09-10T04:00:00Z"));
        }

        @Test
        @DisplayName("미래 시각 판정은 도메인에 맡기고 파싱값을 그대로 넘긴다")
        void 미래_시각() {
            NewsFetchResult result = client(StubHttpClient.ok("dates.xml")).fetch(SOURCE);

            assertThat(result.items().get(3).parsedPublishedAt())
                    .isEqualTo(Instant.parse("2026-09-11T14:00:00Z"));
        }
    }

    @Nested
    @DisplayName("조건부 요청 (spec FR-013)")
    class Conditional {

        @Test
        @DisplayName("직전 ETag와 Last-Modified를 요청에 실어 보낸다")
        void 조건부_헤더_전송() {
            StubHttpClient http = StubHttpClient.ok("valid.xml");
            RssNewsSource source =
                    new RssNewsSource(
                            1L,
                            "example-press",
                            "https://rss.example.co.kr/economy.xml",
                            "\"etag-0\"",
                            "Wed, 09 Sep 2026 04:00:00 GMT");

            client(http).fetch(source);

            assertThat(http.seenEtag).isEqualTo("\"etag-0\"");
            assertThat(http.seenLastModified).isEqualTo("Wed, 09 Sep 2026 04:00:00 GMT");
        }

        @Test
        @DisplayName("304는 변경 없음으로 처리하고 기사를 만들지 않는다")
        void 변경_없음() {
            NewsFetchResult result = client(StubHttpClient.notModified()).fetch(SOURCE);

            assertThat(result.notModified()).isTrue();
            assertThat(result.items()).isEmpty();
            assertThat(result.resultCapReached()).isFalse();
            assertThat(result.etag()).isEqualTo("\"etag-1\"");
        }

        @Test
        @DisplayName("200 응답은 새 ETag와 Last-Modified를 돌려준다")
        void 응답_헤더_보존() {
            NewsFetchResult result = client(StubHttpClient.ok("valid.xml")).fetch(SOURCE);

            assertThat(result.notModified()).isFalse();
            assertThat(result.etag()).isEqualTo("\"etag-1\"");
            assertThat(result.lastModified()).isEqualTo("Thu, 10 Sep 2026 04:00:00 GMT");
        }
    }

    @Nested
    @DisplayName("채널 호출 간격 힌트 (plan 수집과 재시도)")
    class ChannelHints {

        @Test
        @DisplayName("ttl과 skipHours, skipDays를 읽는다")
        void 힌트_파싱() {
            NewsFetchResult result = client(StubHttpClient.ok("channel-hints.xml")).fetch(SOURCE);

            assertThat(result.channelHints().ttl()).isEqualTo(Duration.ofMinutes(60));
            assertThat(result.channelHints().skipHours()).containsExactlyInAnyOrder(0, 1, 2);
            assertThat(result.channelHints().skipDays()).containsExactly("sunday");
        }

        @Test
        @DisplayName("힌트가 없으면 비어 있다")
        void 힌트_없음() {
            NewsFetchResult result = client(StubHttpClient.ok("valid.xml")).fetch(SOURCE);

            assertThat(result.channelHints().isEmpty())
                    .as("valid.xml은 ttl 15만 있어 비어 있지 않다")
                    .isFalse();
            assertThat(result.channelHints().skipHours()).isEmpty();
        }

        @Test
        @DisplayName("기본 간격보다 긴 ttl을 따른다")
        void ttl_적용() {
            Instant from = Instant.parse("2026-09-10T10:00:00Z");
            com.ssafy.thorp.news.application.result.RssChannelHints hints =
                    client(StubHttpClient.ok("channel-hints.xml")).fetch(SOURCE).channelHints();

            assertThat(hints.nextAttemptFrom(from, Duration.ofMinutes(15)))
                    .as("매체가 60분을 요청했으므로 15분이 아니라 60분 뒤다")
                    .isEqualTo(Instant.parse("2026-09-10T11:00:00Z"));
        }

        @Test
        @DisplayName("피하라고 한 시간대는 지나서 부른다")
        void skip_시간대() {
            com.ssafy.thorp.news.application.result.RssChannelHints hints =
                    client(StubHttpClient.ok("channel-hints.xml")).fetch(SOURCE).channelHints();

            // 23:30 + 60분 = 다음 날 00:30. 0시는 피하라고 했으므로 3시로 밀린다.
            Instant next =
                    hints.nextAttemptFrom(
                            Instant.parse("2026-09-10T23:30:00Z"), Duration.ofMinutes(15));

            assertThat(next.atZone(java.time.ZoneOffset.UTC).getHour()).isEqualTo(3);
        }
    }

    @Nested
    @DisplayName("잘못된 응답")
    class BadResponses {

        @Test
        @DisplayName("외부 엔티티가 든 XML은 응답 전체를 격리한다")
        void 외부_엔티티() {
            assertThatThrownBy(() -> client(StubHttpClient.ok("xxe.xml")).fetch(SOURCE))
                    .isInstanceOf(NewsSourceClient.NewsSourceException.class)
                    .hasMessage(RssNewsClient.MALFORMED_XML);
        }

        @Test
        @DisplayName("깨진 XML은 응답 전체를 격리한다")
        void 깨진_xml() {
            assertThatThrownBy(() -> client(StubHttpClient.ok("malformed.xml")).fetch(SOURCE))
                    .isInstanceOf(NewsSourceClient.NewsSourceException.class)
                    .hasMessage(RssNewsClient.MALFORMED_XML);
        }

        @Test
        @DisplayName("허용하지 않는 content-type은 거부한다")
        void 잘못된_content_type() {
            NewsHttpClient http =
                    (url, kind, etag, lastModified) ->
                            new NewsHttpResponse(
                                    200,
                                    "text/html",
                                    "<html/>".getBytes(StandardCharsets.UTF_8),
                                    null,
                                    null);

            assertThatThrownBy(() -> client(http).fetch(SOURCE))
                    .isInstanceOf(NewsSourceClient.NewsSourceException.class)
                    .hasMessage(NewsHttpSecurityPolicy.UNSUPPORTED_CONTENT_TYPE);
        }
    }
}
