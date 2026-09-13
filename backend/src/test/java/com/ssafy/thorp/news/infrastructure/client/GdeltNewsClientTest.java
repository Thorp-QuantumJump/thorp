package com.ssafy.thorp.news.infrastructure.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ssafy.thorp.news.application.port.GdeltNewsSource;
import com.ssafy.thorp.news.application.port.NewsSourceClient;
import com.ssafy.thorp.news.application.result.CollectedNewsItem;
import com.ssafy.thorp.news.application.result.NewsFetchResult;
import com.ssafy.thorp.news.infrastructure.client.NewsHttpSecurityPolicy.SourceKind;
import com.ssafy.thorp.news.infrastructure.config.NewsProperties;
import com.ssafy.thorp.news.infrastructure.config.NewsPublisherRegistry;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * GDELT 보완 수집(spec FR-013, research Decision 3).
 *
 * <p>확인하는 것은 셋이다. 구간과 주제로 조회를 제한한다. 결과 상한을 숨기지 않는다. GDELT 기록 시각을 기사 발행 시각으로 쓰지 않는다.
 */
class GdeltNewsClientTest {

    private static final GdeltNewsSource WINDOW =
            new GdeltNewsSource(
                    "gdelt:doc",
                    Instant.parse("2026-09-10T04:00:00Z"),
                    Instant.parse("2026-09-10T05:00:00Z"),
                    List.of("semiconductor", "prices"));

    private static NewsHttpSecurityPolicy policy() {
        return new NewsHttpSecurityPolicy(
                new NewsProperties.Http(
                        3,
                        Duration.ofSeconds(5),
                        Duration.ofSeconds(20),
                        10_485_760L,
                        Set.of("application/xml"),
                        Set.of("application/json"),
                        List.of("api.gdeltproject.org", "data.gdeltproject.org")),
                new NewsPublisherRegistry("news-publishers-test", List.of()));
    }

    private static byte[] fixture(String name) {
        try (InputStream in =
                GdeltNewsClientTest.class.getResourceAsStream("/news/gdelt/" + name)) {
            if (in == null) {
                throw new IllegalStateException("fixture를 찾을 수 없다: " + name);
            }
            return in.readAllBytes();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** 고정 응답을 돌려주는 대역. 호출된 주소를 모두 기억한다. */
    private static final class StubHttpClient implements NewsHttpClient {

        private final String contentType;
        private final byte[] body;
        private final List<URI> requested = new ArrayList<>();

        private StubHttpClient(String contentType, byte[] body) {
            this.contentType = contentType;
            this.body = body;
        }

        static StubHttpClient json(String fixtureName) {
            return new StubHttpClient("application/json", fixture(fixtureName));
        }

        /**
         * 배포 파일 대역. GDELT 2.0은 zip으로만 내려 주므로 fixture를 그 형태로 감싼다.
         *
         * <p>저장소에는 표 파일을 그대로 두고 시험할 때 압축한다. zip 바이트를 저장소에 넣으면 무엇이 들었는지 diff로 볼 수 없다.
         */
        static StubHttpClient exportZip(String fixtureName) {
            return new StubHttpClient("application/zip", zip(fixtureName, fixture(fixtureName)));
        }

        /** 압축하지 않고 그대로 주는 서버를 흉내 낸다. */
        static StubHttpClient csv(String fixtureName) {
            return new StubHttpClient("text/csv", fixture(fixtureName));
        }

        /**
         * 작게 압축되지만 풀면 {@code decompressedBytes}가 되는 응답을 만든다.
         *
         * <p>같은 내용이 반복되면 거의 무한히 압축된다. 응답 본문 한도(10MiB)는 통과하면서 푼 뒤에만 커지는, 막으려는 바로 그 형태다.
         *
         * <p>{@code lineBreaks}가 참이면 줄바꿈이 있는 정상 모양이라 전체 크기 상한에 걸리고, 거짓이면 전체가 한 줄이라 줄 길이 상한에 걸린다. 둘은
         * 다른 방어막이므로 따로 확인해야 한다.
         */
        static StubHttpClient zipBomb(long decompressedBytes, boolean lineBreaks) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            java.util.Arrays.fill(chunk, (byte) 'x');
            if (lineBreaks) {
                chunk[chunk.length - 1] = '\n';
            }
            try (java.util.zip.ZipOutputStream zip = new java.util.zip.ZipOutputStream(out)) {
                zip.putNextEntry(new java.util.zip.ZipEntry("export.CSV"));
                for (long written = 0; written < decompressedBytes; written += chunk.length) {
                    zip.write(chunk);
                }
                zip.closeEntry();
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
            return new StubHttpClient("application/zip", out.toByteArray());
        }

        private static byte[] zip(String entryName, byte[] body) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            try (java.util.zip.ZipOutputStream zip = new java.util.zip.ZipOutputStream(out)) {
                zip.putNextEntry(new java.util.zip.ZipEntry(entryName));
                zip.write(body);
                zip.closeEntry();
            } catch (java.io.IOException e) {
                throw new IllegalStateException(e);
            }
            return out.toByteArray();
        }

        @Override
        public NewsHttpResponse get(URI url, SourceKind kind, String etag, String lastModified) {
            requested.add(url);
            return new NewsHttpResponse(200, contentType, body, null, null);
        }
    }

    @Nested
    @DisplayName("DOC API 조회")
    class DocApi {

        @Test
        @DisplayName("요청 구간과 주제로 조회를 제한한다")
        void 조회_제한() {
            StubHttpClient http = StubHttpClient.json("doc-response.json");

            new GdeltDocNewsClient(http, policy(), 3).fetch(WINDOW);

            assertThat(http.requested).hasSize(1);
            String query = http.requested.get(0).getQuery();
            assertThat(query).contains("startdatetime=20260910040000");
            assertThat(query).contains("enddatetime=20260910050000");
            assertThat(query).contains("maxrecords=3");
            assertThat(query).as("장기 전체 적재를 하지 않는다").doesNotContain("timespan=full");
        }

        @Test
        @DisplayName("기록 시각을 발행 시각으로 쓰지 않는다")
        void 기록_시각_분리() {
            NewsFetchResult result =
                    new GdeltDocNewsClient(StubHttpClient.json("doc-response.json"), policy(), 10)
                            .fetch(WINDOW);

            CollectedNewsItem first = result.items().get(0);
            assertThat(first.recordedAt()).isEqualTo(Instant.parse("2026-09-10T04:15:00Z"));
            assertThat(first.parsedPublishedAt())
                    .as("seendate는 GDELT가 본 시각이지 매체가 발행한 시각이 아니다")
                    .isNull();
        }

        @Test
        @DisplayName("제목과 주소, 언어를 읽는다")
        void 메타데이터() {
            NewsFetchResult result =
                    new GdeltDocNewsClient(StubHttpClient.json("doc-response.json"), policy(), 10)
                            .fetch(WINDOW);

            assertThat(result.items()).hasSize(2);
            assertThat(result.items().get(0).title()).isEqualTo("Korea chip exports data released");
            assertThat(result.items().get(0).url())
                    .isEqualTo("https://press.overseas.example.com/markets/9001");
            assertThat(result.items().get(0).language()).isEqualTo("en");
        }

        @Test
        @DisplayName("조회에 쓴 주제를 기사에 실어 보낸다")
        void 조회_주제_전달() {
            NewsFetchResult result =
                    new GdeltDocNewsClient(StubHttpClient.json("doc-response.json"), policy(), 10)
                            .fetch(WINDOW);

            assertThat(result.items())
                    .allSatisfy(
                            item ->
                                    assertThat(item.sourceCategories())
                                            .as("비워 두면 관련성 필터가 전량 거부한다")
                                            .containsExactly("semiconductor", "prices"));
        }

        @Test
        @DisplayName("받은 수가 상한과 같으면 결과 상한 도달로 표시한다")
        void 결과_상한() {
            NewsFetchResult result =
                    new GdeltDocNewsClient(StubHttpClient.json("doc-capped.json"), policy(), 3)
                            .fetch(WINDOW);

            assertThat(result.resultCapReached()).as("구간을 다 받지 못했을 수 있다는 사실을 숨기지 않는다").isTrue();
            assertThat(result.items()).hasSize(3);
        }

        @Test
        @DisplayName("상한에 못 미치면 완전 수집이다")
        void 상한_미달() {
            NewsFetchResult result =
                    new GdeltDocNewsClient(StubHttpClient.json("doc-response.json"), policy(), 10)
                            .fetch(WINDOW);

            assertThat(result.resultCapReached()).isFalse();
        }

        @Test
        @DisplayName("빈 결과는 정상이다")
        void 빈_결과() {
            NewsFetchResult result =
                    new GdeltDocNewsClient(StubHttpClient.json("doc-empty.json"), policy(), 10)
                            .fetch(WINDOW);

            assertThat(result.items()).isEmpty();
            assertThat(result.resultCapReached()).isFalse();
        }

        @Test
        @DisplayName("필수 필드가 없는 레코드만 제외한다")
        void 필수_필드_누락() {
            NewsFetchResult result =
                    new GdeltDocNewsClient(
                                    StubHttpClient.json("doc-missing-fields.json"), policy(), 10)
                            .fetch(WINDOW);

            assertThat(result.items()).as("제목 없는 레코드와 주소 없는 레코드는 표시 후보가 아니다").hasSize(1);
            assertThat(result.items().get(0).title()).isEqualTo("정상 레코드");
        }

        @Test
        @DisplayName("깨진 JSON은 응답 전체를 격리한다")
        void 깨진_json() {
            assertThatThrownBy(
                            () ->
                                    new GdeltDocNewsClient(
                                                    StubHttpClient.json("doc-malformed.json"),
                                                    policy(),
                                                    10)
                                            .fetch(WINDOW))
                    .isInstanceOf(NewsSourceClient.NewsSourceException.class)
                    .hasMessage(GdeltDocNewsClient.MALFORMED_JSON);
        }
    }

    @Nested
    @DisplayName("배포 파일 보완 수집")
    class ExportFile {

        @Test
        @DisplayName("구간에 필요한 파일만 오래된 순으로 읽는다")
        void 오래된_순() {
            StubHttpClient http = StubHttpClient.exportZip("export-window.csv");
            GdeltNewsSource twoSlots =
                    new GdeltNewsSource(
                            "gdelt:file",
                            Instant.parse("2026-09-10T04:00:00Z"),
                            Instant.parse("2026-09-10T04:30:00Z"),
                            List.of());

            new GdeltFileNewsClient(http, policy()).fetch(twoSlots);

            assertThat(http.requested).hasSize(2);
            assertThat(http.requested.get(0).toString())
                    .as("오래된 구간을 먼저 읽는다")
                    .contains("20260910040000");
            assertThat(http.requested.get(1).toString()).contains("20260910041500");
            assertThat(http.requested.get(0).toString())
                    .as("GDELT 2.0은 배포 파일을 zip으로만 내려 준다. 이름이 틀리면 운영에서 매 회차가 헛돈다")
                    .endsWith(".export.CSV.zip");
        }

        @Test
        @DisplayName("압축하지 않고 그대로 주는 응답도 읽는다")
        void 비압축_응답() {
            NewsFetchResult result =
                    new GdeltFileNewsClient(StubHttpClient.csv("export-window.csv"), policy())
                            .fetch(
                                    new GdeltNewsSource(
                                            "gdelt:file",
                                            Instant.parse("2026-09-10T04:00:00Z"),
                                            Instant.parse("2026-09-10T04:15:00Z"),
                                            List.of()));

            assertThat(result.items()).hasSize(2);
        }

        @Test
        @DisplayName("zip이라고 했는데 열리지 않으면 그 응답을 격리한다")
        void 깨진_zip() {
            StubHttpClient broken =
                    new StubHttpClient(
                            "application/zip", new byte[] {'P', 'K', 3, 4, 0, 0, 0, 0, 0});

            assertThatThrownBy(
                            () ->
                                    new GdeltFileNewsClient(broken, policy())
                                            .fetch(
                                                    new GdeltNewsSource(
                                                            "gdelt:file",
                                                            Instant.parse("2026-09-10T04:00:00Z"),
                                                            Instant.parse("2026-09-10T04:15:00Z"),
                                                            List.of())))
                    .isInstanceOf(NewsSourceClient.NewsSourceException.class)
                    .hasMessage(GdeltFileNewsClient.MALFORMED_EXPORT);
        }

        @Test
        @DisplayName("작게 압축됐어도 풀어서 한도를 넘으면 읽다가 끊는다")
        void 압축_폭탄() {
            // 푼 크기 한도(128MiB)를 갓 넘긴다. 압축된 응답은 수백 KB라 본문 한도에는 걸리지 않는다.
            StubHttpClient bomb = StubHttpClient.zipBomb(129L * 1024 * 1024, true);

            assertThatThrownBy(() -> fetchOneSlot(bomb))
                    .as("푼 내용을 다 모은 뒤에 재면 이미 메모리를 쓴 다음이다")
                    .isInstanceOf(NewsSourceClient.NewsSourceException.class)
                    .hasMessage(GdeltFileNewsClient.MALFORMED_EXPORT);
        }

        @Test
        @DisplayName("줄바꿈이 없어 한 줄이 계속 길어지면 전체 한도에 닿기 전에 끊는다")
        void 줄바꿈_없는_폭탄() {
            // 줄 길이 상한(1MiB)만 넘고 전체 상한(128MiB)에는 못 미치는 크기.
            StubHttpClient bomb = StubHttpClient.zipBomb(8L * 1024 * 1024, false);

            assertThatThrownBy(() -> fetchOneSlot(bomb))
                    .as("줄로 만든 뒤에 재면 이미 그 줄이 통째로 메모리에 올라와 있다")
                    .isInstanceOf(NewsSourceClient.NewsSourceException.class)
                    .hasMessage(GdeltFileNewsClient.MALFORMED_EXPORT);
        }

        private NewsFetchResult fetchOneSlot(StubHttpClient http) {
            return new GdeltFileNewsClient(http, policy())
                    .fetch(
                            new GdeltNewsSource(
                                    "gdelt:file",
                                    Instant.parse("2026-09-10T04:00:00Z"),
                                    Instant.parse("2026-09-10T04:15:00Z"),
                                    List.of()));
        }

        @Test
        @DisplayName("배포 파일에서 온 기사는 DOC과 다른 출처로 표시된다")
        void 출처_구분() {
            NewsFetchResult file =
                    new GdeltFileNewsClient(StubHttpClient.exportZip("export-window.csv"), policy())
                            .fetch(
                                    new GdeltNewsSource(
                                            "gdelt:file",
                                            Instant.parse("2026-09-10T04:00:00Z"),
                                            Instant.parse("2026-09-10T04:15:00Z"),
                                            List.of()));
            NewsFetchResult doc =
                    new GdeltDocNewsClient(StubHttpClient.json("doc-response.json"), policy(), 10)
                            .fetch(WINDOW);

            assertThat(file.items())
                    .allSatisfy(
                            item ->
                                    assertThat(item.sourceType())
                                            .isEqualTo(
                                                    com.ssafy.thorp.news.domain.vo.NewsSourceType
                                                            .GDELT_FILE));
            assertThat(doc.items())
                    .as("둘을 합쳐도 어디서 왔는지 남아야 한다")
                    .allSatisfy(
                            item ->
                                    assertThat(item.sourceType())
                                            .isEqualTo(
                                                    com.ssafy.thorp.news.domain.vo.NewsSourceType
                                                            .GDELT_DOC));
        }

        @Test
        @DisplayName("제목이나 주소가 없는 레코드는 표시 후보로 만들지 않는다")
        void 표시_불가_레코드() {
            NewsFetchResult result =
                    new GdeltFileNewsClient(StubHttpClient.exportZip("export-window.csv"), policy())
                            .fetch(
                                    new GdeltNewsSource(
                                            "gdelt:file",
                                            Instant.parse("2026-09-10T04:00:00Z"),
                                            Instant.parse("2026-09-10T04:15:00Z"),
                                            List.of()));

            assertThat(result.items()).as("fixture 4행 중 제목·주소를 모두 가진 2행만 남는다").hasSize(2);
            assertThat(result.items())
                    .allSatisfy(item -> assertThat(item.parsedPublishedAt()).isNull());
            assertThat(result.items().get(0).recordedAt())
                    .isEqualTo(Instant.parse("2026-09-10T04:15:00Z"));
        }
    }
}
