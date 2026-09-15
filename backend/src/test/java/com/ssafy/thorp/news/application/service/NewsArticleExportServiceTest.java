package com.ssafy.thorp.news.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ssafy.thorp.news.application.command.NewsArticleExportQuery;
import com.ssafy.thorp.news.application.exception.NewsQueryException;
import com.ssafy.thorp.news.application.repository.NewsArticleRepository;
import com.ssafy.thorp.news.application.repository.NewsPublisherPermissionHistoryRepository;
import com.ssafy.thorp.news.application.repository.NewsPublisherRepository;
import com.ssafy.thorp.news.application.result.NewsArticleExportResult;
import com.ssafy.thorp.news.domain.model.NewsArticle;
import com.ssafy.thorp.news.domain.model.NewsPublisher;
import com.ssafy.thorp.news.domain.model.NewsPublisherPermissionHistory;
import com.ssafy.thorp.news.domain.vo.CanonicalNewsUrl;
import com.ssafy.thorp.news.domain.vo.NewsAllowedField;
import com.ssafy.thorp.news.domain.vo.NewsArticleId;
import com.ssafy.thorp.news.domain.vo.NewsExposureMode;
import com.ssafy.thorp.news.domain.vo.NewsSourceType;
import com.ssafy.thorp.news.domain.vo.PermissionStatus;
import com.ssafy.thorp.news.domain.vo.PublicationTimeStatus;
import com.ssafy.thorp.news.support.StubSectorCatalog;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * 내보내기가 허가 범위를 지키는지(spec FR-017, SC-008).
 *
 * <p>내보낸 자료는 우리 화면을 떠나 다른 곳에 저장된다. 화면은 닫으면 사라지지만 내보낸 파일은 남으므로, 여기서 한 번 새면 회수할 방법이 없다.
 */
class NewsArticleExportServiceTest {

    private static final Instant FROM = Instant.parse("2026-09-01T00:00:00Z");
    private static final Instant TO = Instant.parse("2026-09-30T00:00:00Z");
    private static final Instant PUBLISHED_AT = Instant.parse("2026-09-10T04:00:00Z");

    private static NewsPublisher publisher(long id, String key, PermissionStatus status) {
        return NewsPublisher.restore(
                id,
                key,
                key + " 신문",
                "ko",
                key + ".example.com",
                "https://rss." + key + ".example.com/economy.xml",
                status,
                PUBLISHED_AT,
                true,
                null,
                null);
    }

    private static NewsArticle article(long id, long publisherId, String url, Instant publishedAt) {
        CanonicalNewsUrl canonical = CanonicalNewsUrl.normalize(url);
        return NewsArticle.restore(
                id,
                NewsArticleId.of(canonical),
                NewsSourceType.RSS,
                publisherId,
                null,
                canonical.value(),
                canonical,
                "제목 " + id,
                "ko",
                publishedAt,
                publishedAt == null
                        ? PublicationTimeStatus.UNKNOWN
                        : PublicationTimeStatus.VERIFIED,
                PUBLISHED_AT,
                null,
                canonical.value(),
                "hash",
                "run-1",
                true);
    }

    private static NewsArticleExportService service(
            List<NewsPublisher> publishers,
            List<NewsArticle> articles,
            Map<Long, Set<NewsAllowedField>> allowedByPublisher) {
        return new NewsArticleExportService(
                new ArticleStore(articles),
                new PublisherStore(publishers),
                new PermissionStore(allowedByPublisher),
                new NewsSectorQueryPolicy(new StubSectorCatalog()),
                new NewsDigestQueryService.NewsQuerySettings(
                        NewsExposureMode.PUBLIC,
                        Duration.ofMinutes(30),
                        3,
                        Duration.ofHours(48),
                        93));
    }

    private static NewsArticleExportQuery query(int limit) {
        return new NewsArticleExportQuery(null, FROM, TO, null, limit);
    }

    @Nested
    @DisplayName("허가 필터")
    class Permission {

        @Test
        @DisplayName("회신이 없는 매체의 기사는 나가지 않는다")
        void 미허가_매체_제외() {
            NewsArticleExportResult result =
                    service(
                                    List.of(
                                            publisher(1L, "allowed", PermissionStatus.ALLOWED),
                                            publisher(2L, "pending", PermissionStatus.PENDING)),
                                    List.of(
                                            article(
                                                    10L,
                                                    1L,
                                                    "https://a.example.com/1",
                                                    PUBLISHED_AT),
                                            article(
                                                    11L,
                                                    2L,
                                                    "https://b.example.com/1",
                                                    PUBLISHED_AT)),
                                    Map.of(1L, Set.of(NewsAllowedField.TITLE)))
                            .export(query(10));

            assertThat(result.items()).as("내보낸 파일은 남는다. 여기서 한 번 새면 회수할 방법이 없다").hasSize(1);
            assertThat(result.items().get(0).title()).isEqualTo("제목 10");
        }

        @Test
        @DisplayName("불허로 바뀐 매체의 기사도 나가지 않는다")
        void 불허_매체_제외() {
            NewsArticleExportResult result =
                    service(
                                    List.of(publisher(1L, "denied", PermissionStatus.DENIED)),
                                    List.of(
                                            article(
                                                    10L,
                                                    1L,
                                                    "https://a.example.com/1",
                                                    PUBLISHED_AT)),
                                    Map.of(1L, Set.of(NewsAllowedField.TITLE)))
                            .export(query(10));

            assertThat(result.items()).isEmpty();
        }

        @Test
        @DisplayName("허가 매체가 하나도 없으면 빈 결과다")
        void 허가_매체_없음() {
            NewsArticleExportResult result =
                    service(
                                    List.of(publisher(1L, "pending", PermissionStatus.PENDING)),
                                    List.of(
                                            article(
                                                    10L,
                                                    1L,
                                                    "https://a.example.com/1",
                                                    PUBLISHED_AT)),
                                    Map.of())
                            .export(query(10));

            assertThat(result.items()).isEmpty();
            assertThat(result.nextCursor()).isNull();
        }
    }

    @Nested
    @DisplayName("허용 필드 투영")
    class Projection {

        @Test
        @DisplayName("회신이 허용한 필드만 채운다")
        void 허용된_필드만() {
            NewsArticleExportResult.Item item =
                    service(
                                    List.of(publisher(1L, "allowed", PermissionStatus.ALLOWED)),
                                    List.of(
                                            article(
                                                    10L,
                                                    1L,
                                                    "https://a.example.com/1",
                                                    PUBLISHED_AT)),
                                    Map.of(
                                            1L,
                                            Set.of(
                                                    NewsAllowedField.TITLE,
                                                    NewsAllowedField.ORIGINAL_URL)))
                            .export(query(10))
                            .items()
                            .get(0);

            assertThat(item.title()).isNotNull();
            assertThat(item.originalUrl()).isNotNull();
            assertThat(item.publisher()).as("'허가는 했지만 매체명은 빼 달라'는 회신을 상태만 보고는 지킬 수 없다").isNull();
            assertThat(item.publishedAt()).isNull();
        }

        @Test
        @DisplayName("식별자와 발행 시각 확인 여부는 늘 담는다")
        void 늘_담는_필드() {
            NewsArticleExportResult.Item item =
                    service(
                                    List.of(publisher(1L, "allowed", PermissionStatus.ALLOWED)),
                                    List.of(article(10L, 1L, "https://a.example.com/1", null)),
                                    Map.of(1L, Set.of(NewsAllowedField.TITLE)))
                            .export(query(10))
                            .items()
                            .get(0);

            assertThat(item.articleId())
                    .as("우리가 canonical URL에서 만든 값이라 매체에 관한 정보가 아니다")
                    .matches("^[a-z2-7]{32}$");
            assertThat(item.publicationTimeStatus()).isEqualTo(PublicationTimeStatus.UNKNOWN);
        }

        @Test
        @DisplayName("회신 기록이 없으면 어떤 필드도 열지 않는다")
        void 회신_기록_없음() {
            NewsArticleExportResult.Item item =
                    service(
                                    List.of(publisher(1L, "allowed", PermissionStatus.ALLOWED)),
                                    List.of(
                                            article(
                                                    10L,
                                                    1L,
                                                    "https://a.example.com/1",
                                                    PUBLISHED_AT)),
                                    Map.of())
                            .export(query(10))
                            .items()
                            .get(0);

            assertThat(item.title()).as("있을 수 없는 상태지만 그때 열어 주는 쪽으로 틀리면 허가 없이 자료가 나간다").isNull();
            assertThat(item.publisher()).isNull();
            assertThat(item.originalUrl()).isNull();
        }
    }

    @Nested
    @DisplayName("페이지 넘김")
    class Paging {

        private static NewsArticleExportService pagingService(int articleCount) {
            List<NewsArticle> articles = new ArrayList<>();
            for (int i = 0; i < articleCount; i++) {
                articles.add(
                        article(
                                100L + i,
                                1L,
                                "https://a.example.com/" + i,
                                PUBLISHED_AT.plusSeconds(i)));
            }
            return service(
                    List.of(publisher(1L, "allowed", PermissionStatus.ALLOWED)),
                    articles,
                    Map.of(1L, Set.of(NewsAllowedField.TITLE)));
        }

        @Test
        @DisplayName("마지막 페이지에는 다음 자리가 없다")
        void 마지막_페이지() {
            NewsArticleExportResult result = pagingService(3).export(query(10));

            assertThat(result.items()).hasSize(3);
            assertThat(result.nextCursor()).as("더 없는데 자리를 주면 클라이언트가 빈 페이지를 한 번 더 받는다").isNull();
        }

        @Test
        @DisplayName("cursor로 이어 받으면 같은 기사를 두 번 받지 않는다")
        void 이어받기() {
            NewsArticleExportService service = pagingService(5);

            NewsArticleExportResult first = service.export(query(2));
            assertThat(first.items()).hasSize(2);
            assertThat(first.nextCursor()).isNotNull();

            NewsArticleExportResult second =
                    service.export(
                            NewsArticleExportQuery.of(null, FROM, TO, first.nextCursor(), 2, 93));

            assertThat(second.items()).hasSize(2);
            assertThat(second.items())
                    .extracting(NewsArticleExportResult.Item::articleId)
                    .doesNotContainAnyElementsOf(
                            first.items().stream()
                                    .map(NewsArticleExportResult.Item::articleId)
                                    .toList());
        }

        @Test
        @DisplayName("미허가 매체가 섞여 있어도 페이지를 채운다")
        void 걸러져도_채운다() {
            List<NewsArticle> articles = new ArrayList<>();
            for (int i = 0; i < 10; i++) {
                // 짝수는 허가 매체, 홀수는 회신 전 매체.
                articles.add(
                        article(
                                100L + i,
                                i % 2 == 0 ? 1L : 2L,
                                "https://a.example.com/" + i,
                                PUBLISHED_AT.plusSeconds(i)));
            }
            NewsArticleExportResult result =
                    service(
                                    List.of(
                                            publisher(1L, "allowed", PermissionStatus.ALLOWED),
                                            publisher(2L, "pending", PermissionStatus.PENDING)),
                                    articles,
                                    Map.of(1L, Set.of(NewsAllowedField.TITLE)))
                            .export(query(3));

            assertThat(result.items()).as("모자란 채로 돌려주면 클라이언트가 끝에 닿았다고 오해한다").hasSize(3);
        }

        @Test
        @DisplayName("필터로 여러 raw 페이지를 읽어도 다음 허가 기사를 건너뛰지 않는다")
        void 필터_뒤_다음_페이지_누락_없음() {
            List<NewsArticle> articles =
                    List.of(
                            article(100L, 2L, "https://a.example.com/0", PUBLISHED_AT),
                            article(
                                    101L,
                                    1L,
                                    "https://a.example.com/1",
                                    PUBLISHED_AT.plusSeconds(1)),
                            article(
                                    102L,
                                    2L,
                                    "https://a.example.com/2",
                                    PUBLISHED_AT.plusSeconds(2)),
                            article(
                                    103L,
                                    1L,
                                    "https://a.example.com/3",
                                    PUBLISHED_AT.plusSeconds(3)),
                            article(
                                    104L,
                                    1L,
                                    "https://a.example.com/4",
                                    PUBLISHED_AT.plusSeconds(4)));
            NewsArticleExportService service =
                    service(
                            List.of(
                                    publisher(1L, "allowed", PermissionStatus.ALLOWED),
                                    publisher(2L, "pending", PermissionStatus.PENDING)),
                            articles,
                            Map.of(1L, Set.of(NewsAllowedField.TITLE)));

            NewsArticleExportResult first = service.export(query(2));
            NewsArticleExportResult second =
                    service.export(
                            NewsArticleExportQuery.of(null, FROM, TO, first.nextCursor(), 2, 93));

            assertThat(first.items())
                    .extracting(NewsArticleExportResult.Item::title)
                    .containsExactly("제목 101", "제목 103");
            assertThat(second.items())
                    .extracting(NewsArticleExportResult.Item::title)
                    .containsExactly("제목 104");
        }
    }

    @Nested
    @DisplayName("요청 검증")
    class Validation {

        @Test
        @DisplayName("기간을 한쪽만 주면 거부한다")
        void 기간_필수() {
            assertThatThrownBy(() -> NewsArticleExportQuery.of(null, FROM, null, null, null, 93))
                    .isInstanceOf(NewsQueryException.class);
        }

        @Test
        @DisplayName("페이지 크기 상한을 넘으면 거부한다")
        void 페이지_상한() {
            assertThatThrownBy(
                            () ->
                                    NewsArticleExportQuery.of(
                                            null,
                                            FROM,
                                            TO,
                                            null,
                                            NewsArticleExportQuery.MAX_LIMIT + 1,
                                            93))
                    .isInstanceOf(NewsQueryException.class);
        }

        @Test
        @DisplayName("해석할 수 없는 cursor는 거부한다")
        void 깨진_cursor() {
            assertThatThrownBy(
                            () ->
                                    NewsArticleExportQuery.of(
                                            null, FROM, TO, "!!not-base64!!", null, 93))
                    .as("조용히 첫 페이지로 되돌리면 같은 페이지를 끝없이 받으면서도 그 사실을 모른다")
                    .isInstanceOf(NewsQueryException.class);
        }

        @Test
        @DisplayName("cursor는 왕복해도 같은 자리를 가리킨다")
        void cursor_왕복() {
            NewsArticleExportQuery.Cursor cursor =
                    new NewsArticleExportQuery.Cursor(
                            PUBLISHED_AT, "abcdefghijklmnopqrstuvwxyz234567");

            assertThat(NewsArticleExportQuery.Cursor.parse(cursor.encode())).contains(cursor);
        }
    }

    /** 기사 저장소 대역. 실제 질의와 같은 규칙으로 거르고 정렬한다. */
    private record ArticleStore(List<NewsArticle> articles) implements NewsArticleRepository {

        @Override
        public List<NewsArticle> findForExport(
                Instant from,
                Instant to,
                String sectorId,
                NewsArticleExportQuery.Cursor after,
                int limit) {
            return articles.stream()
                    .filter(a -> !ordering(a).isBefore(from) && !ordering(a).isAfter(to))
                    .filter(a -> after == null || isAfter(a, after))
                    .sorted(
                            Comparator.comparing(ArticleStore::ordering)
                                    .thenComparing(a -> a.getArticleId().value()))
                    .limit(limit)
                    .toList();
        }

        private static boolean isAfter(NewsArticle article, NewsArticleExportQuery.Cursor cursor) {
            Instant key = ordering(article);
            if (key.isAfter(cursor.orderingInstant())) {
                return true;
            }
            return key.equals(cursor.orderingInstant())
                    && article.getArticleId().value().compareTo(cursor.articleId()) > 0;
        }

        private static Instant ordering(NewsArticle article) {
            return article.getPublishedAt() == null
                    ? article.getCollectedAt()
                    : article.getPublishedAt();
        }

        @Override
        public NewsArticle upsertByCanonicalUrl(NewsArticle article) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<NewsArticle> findByCanonicalUrl(CanonicalNewsUrl canonicalUrl) {
            return Optional.empty();
        }

        @Override
        public Optional<NewsArticle> findByArticleId(NewsArticleId articleId) {
            return Optional.empty();
        }

        @Override
        public Optional<NewsArticle> findByPublisherAndSourceItemId(
                Long publisherId, String sourceItemId) {
            return Optional.empty();
        }

        @Override
        public List<NewsArticle> findVerifiedBetween(Instant from, Instant to) {
            return List.of();
        }

        @Override
        public List<NewsArticle> findDisplayEligibleSince(Instant since) {
            return List.of();
        }
    }

    private record PublisherStore(List<NewsPublisher> publishers)
            implements NewsPublisherRepository {

        @Override
        public NewsPublisher save(NewsPublisher publisher) {
            return publisher;
        }

        @Override
        public Optional<NewsPublisher> findById(Long id) {
            return publishers.stream().filter(p -> p.getId().equals(id)).findFirst();
        }

        @Override
        public Optional<NewsPublisher> findByPublisherKey(String publisherKey) {
            return Optional.empty();
        }

        @Override
        public Optional<NewsPublisher> findByRegisteredDomain(String registeredDomain) {
            return Optional.empty();
        }

        @Override
        public List<NewsPublisher> findEnabled() {
            return publishers;
        }

        @Override
        public List<NewsPublisher> findPubliclyVisible() {
            return publishers.stream().filter(NewsPublisher::isPubliclyVisible).toList();
        }

        @Override
        public boolean existsPubliclyVisible() {
            return !findPubliclyVisible().isEmpty();
        }
    }

    /** 허가 이력 대역. 매체별 최신 회신의 허용 필드만 들고 있다. */
    private record PermissionStore(Map<Long, Set<NewsAllowedField>> allowedByPublisher)
            implements NewsPublisherPermissionHistoryRepository {

        @Override
        public NewsPublisherPermissionHistory appendAndApply(NewsPublisherPermissionHistory event) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<NewsPublisherPermissionHistory> findByPublisherId(Long publisherId) {
            return List.of();
        }

        @Override
        public Optional<NewsPublisherPermissionHistory> findLatestResponse(Long publisherId) {
            return Optional.ofNullable(allowedByPublisher.get(publisherId))
                    .map(
                            fields ->
                                    NewsPublisherPermissionHistory.allowed(
                                            publisherId, fields, PUBLISHED_AT, "회신"));
        }
    }
}
