package com.ssafy.thorp.news.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.ssafy.thorp.common.config.JpaAuditingConfig;
import com.ssafy.thorp.news.application.command.NewsDigestQuery;
import com.ssafy.thorp.news.application.result.NewsDigestListResult;
import com.ssafy.thorp.news.application.service.NewsDigestQueryService;
import com.ssafy.thorp.news.domain.model.NewsArticle;
import com.ssafy.thorp.news.domain.model.NewsDigest;
import com.ssafy.thorp.news.domain.model.NewsPublisher;
import com.ssafy.thorp.news.domain.vo.CanonicalNewsUrl;
import com.ssafy.thorp.news.domain.vo.NewsDigestState;
import com.ssafy.thorp.news.domain.vo.NewsExposureMode;
import com.ssafy.thorp.news.domain.vo.NewsSourceType;
import com.ssafy.thorp.news.domain.vo.PermissionStatus;
import com.ssafy.thorp.news.domain.vo.PublicationTimeStatus;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

/**
 * 공개 조회 투영의 순서를 실제 DB에서 고정한다(spec FR-016, T041).
 *
 * <p>규칙은 <b>허가 필터 → 묶음 재계산 → 주요도 정렬 → 상위 N개</b>다. 이 차례가 뒤집혀도 대부분의 시험은 통과한다 — 미허가 항목이 결과에 없다는 것은 양쪽
 * 순서에서 모두 참이기 때문이다. 차이가 드러나는 자리는 <b>상한에 걸릴 때</b> 하나뿐이다. 정렬을 먼저 하면 미허가 묶음이 상위 세 자리를 차지한 뒤 걸러져, 허가된
 * 묶음이 있는데도 화면이 빈다. 그 한 자리를 이 파일이 지킨다.
 *
 * <p>나머지 투영 규칙(저장 값 보존, 대표 교체, 회신 전 매체 제외)은 {@code NewsPersistenceIT}가 이미 본다. 여기서는 그 위에 <b>조회 서비스를
 * 통과한 결과</b>로 대표 교체를 한 번 더 확인한다 — 도메인 메서드를 직접 부르는 것과 서비스가 그것을 제대로 엮는 것은 다른 이야기다.
 *
 * <p>Docker가 없으면 {@link com.ssafy.thorp.support.PostgresTestSupport}가 통째로 skip한다.
 */
@DataJpaTest
@Import({
    JpaAuditingConfig.class,
    NewsPublisherRepositoryImpl.class,
    NewsPublisherPermissionHistoryRepositoryImpl.class,
    NewsArticleRepositoryImpl.class,
    NewsDigestRepositoryImpl.class,
    NewsCollectionStateRepositoryImpl.class,
    NewsArticleLinkRepositoryImpl.class,
    NewsDigestQueryService.class,
    com.ssafy.thorp.news.application.service.NewsSummaryPolicy.class,
    com.ssafy.thorp.news.application.service.NewsImportancePolicy.class,
    com.ssafy.thorp.news.application.service.NewsFreshnessPolicy.class,
    com.ssafy.thorp.news.application.service.NewsSectorQueryPolicy.class,
    NewsPublicProjectionIT.TestBeans.class
})
class NewsPublicProjectionIT extends com.ssafy.thorp.support.PostgresTestSupport {

    private static final Instant NOW = Instant.parse("2026-09-10T06:00:00Z");
    private static final String GROUPING_RULE = "news-grouping-v1";
    private static final String IMPORTANCE_RULE = "news-importance-v1";
    private static final String LINK_RULE = "news-link-v1";
    private static final String SEMICONDUCTOR = "SEMICONDUCTOR";

    /** 서버 설정이 정한 응답 묶음 수 상한. 이 값이 있어야 순서가 드러난다. */
    private static final int MAX_DIGESTS = 3;

    @Autowired private TestEntityManager entityManager;
    @Autowired private NewsPublisherRepositoryImpl publishers;
    @Autowired private NewsArticleRepositoryImpl articles;
    @Autowired private NewsDigestRepositoryImpl digests;
    @Autowired private NewsArticleLinkRepositoryImpl links;
    @Autowired private NewsDigestQueryService queryService;

    @Test
    @DisplayName("허가 필터가 상한보다 먼저 걸려 허가된 묶음이 밀려나지 않는다")
    void 허가가_순위보다_먼저다() {
        NewsPublisher allowed = savePublisher("allowed-press", PermissionStatus.ALLOWED);
        NewsPublisher pending = savePublisher("pending-press", PermissionStatus.PENDING);

        // 회신 전 매체의 묶음을 상한보다 많이, 그리고 더 최근으로 만든다. 정렬을 먼저 하면 이것들이
        // 상위 세 자리를 모두 차지한다.
        for (int i = 0; i < MAX_DIGESTS + 1; i++) {
            saveDigest(pending, "pending-" + i, "회신 전 매체 기사 " + i, NOW.minusSeconds(60L * i));
        }
        saveDigest(allowed, "allowed-1", "허가 매체 기사", NOW.minus(Duration.ofHours(6)));

        NewsDigestListResult result = queryService.query(NewsDigestQuery.current(null));

        assertThat(result.digests()).as("허가된 묶음이 있는데 화면이 비면 허가 필터가 정렬 뒤에 걸린 것이다").hasSize(1);
        assertThat(result.digests().get(0).representativeTitle()).isEqualTo("허가 매체 기사");
        assertThat(result.state()).isEqualTo(NewsDigestState.READY);
    }

    @Test
    @DisplayName("혼합 묶음은 허가된 기사로 대표와 매체 수를 다시 만든다")
    void 혼합_묶음_대표_교체() {
        NewsPublisher allowed = savePublisher("mixed-allowed", PermissionStatus.ALLOWED);
        NewsPublisher denied = savePublisher("mixed-denied", PermissionStatus.DENIED);

        NewsArticle deniedArticle =
                saveArticle(
                        denied,
                        "https://rss.mixed-denied.example.com/1",
                        "불허 매체 제목",
                        NOW.minus(Duration.ofHours(5)));
        NewsArticle allowedArticle =
                saveArticle(
                        allowed,
                        "https://rss.mixed-allowed.example.com/1",
                        "허가 매체 제목",
                        NOW.minus(Duration.ofHours(4)));
        linkTopic(deniedArticle);
        linkTopic(allowedArticle);
        digests.save(
                NewsDigest.of(
                        List.of(deniedArticle, allowedArticle),
                        "요약",
                        GROUPING_RULE,
                        IMPORTANCE_RULE));
        flushAndClear();

        NewsDigestListResult result = queryService.query(NewsDigestQuery.current(null));

        assertThat(result.digests()).hasSize(1);
        NewsDigestListResult.Digest digest = result.digests().get(0);
        assertThat(digest.representativeTitle()).isEqualTo("허가 매체 제목");
        assertThat(digest.factualSummary())
                .as("요약 문장에 불허 매체의 제목이 남으면 걸러 낸 뜻이 없다")
                .doesNotContain("불허 매체 제목");
        assertThat(digest.publisherCount()).as("매체 수에 불허 매체를 세면 그 존재가 숫자로 새어 나간다").isEqualTo(1);
        assertThat(digest.articles())
                .extracting(NewsDigestListResult.Article::title)
                .containsExactly("허가 매체 제목");
    }

    @Test
    @DisplayName("불허로 돌아선 매체의 묶음은 화면에서 사라진다")
    void 불허_매체는_사라진다() {
        NewsPublisher publisher = savePublisher("revoked-press", PermissionStatus.ALLOWED);
        saveDigest(publisher, "revoked-1", "허가 시절 기사", NOW.minus(Duration.ofHours(2)));

        assertThat(queryService.query(NewsDigestQuery.current(null)).digests()).hasSize(1);

        publishers.save(publisher.withPermission(PermissionStatus.DENIED, NOW));
        flushAndClear();

        assertThat(queryService.query(NewsDigestQuery.current(null)).digests())
                .as("허가를 거둬들이면 그때까지 모은 기사도 함께 내려가야 한다")
                .isEmpty();
    }

    @Test
    @DisplayName("회신한 매체가 하나도 없으면 자료가 있어도 준비 중이다")
    void 전부_회신_전이면_준비_중() {
        NewsPublisher pending = savePublisher("all-pending", PermissionStatus.PENDING);
        saveDigest(pending, "pending-only", "회신 전 매체 기사", NOW.minus(Duration.ofHours(1)));

        NewsDigestListResult result = queryService.query(NewsDigestQuery.current(null));

        assertThat(result.state())
                .as("EMPTY로 답하면 '자료가 없다'는 뜻이 되어, 허가를 기다리는 중이라는 사실이 가려진다")
                .isEqualTo(NewsDigestState.PREPARING);
        assertThat(result.digests()).isEmpty();
    }

    /** 기사 하나짜리 묶음을 만들어 저장한다. 주제까지 붙여 요약 근거를 채운다. */
    private void saveDigest(
            NewsPublisher publisher, String slug, String title, Instant publishedAt) {
        NewsArticle article =
                saveArticle(
                        publisher,
                        "https://rss." + publisher.getPublisherKey() + ".example.com/" + slug,
                        title,
                        publishedAt);
        linkTopic(article);
        digests.save(NewsDigest.of(List.of(article), "요약", GROUPING_RULE, IMPORTANCE_RULE));
        flushAndClear();
    }

    private NewsPublisher savePublisher(String key, PermissionStatus status) {
        NewsPublisher publisher =
                publishers.save(
                        NewsPublisher.register(
                                key,
                                key + " 신문",
                                "ko",
                                key + ".example.com",
                                "https://rss." + key + ".example.com/economy.xml",
                                true));
        if (status != PermissionStatus.PENDING) {
            publisher = publishers.save(publisher.withPermission(status, NOW));
        }
        flushAndClear();
        return publisher;
    }

    private NewsArticle saveArticle(
            NewsPublisher publisher, String url, String title, Instant publishedAt) {
        CanonicalNewsUrl canonical = CanonicalNewsUrl.normalize(url);
        PublicationTimeStatus status = NewsArticle.classifyPublicationTime(publishedAt, NOW);
        NewsArticle saved =
                articles.upsertByCanonicalUrl(
                        NewsArticle.collected(
                                NewsSourceType.RSS,
                                publisher.getId(),
                                null,
                                url,
                                canonical,
                                title,
                                "ko",
                                status == PublicationTimeStatus.VERIFIED ? publishedAt : null,
                                status,
                                NOW,
                                null,
                                canonical.value(),
                                "hash",
                                "run-1",
                                true));
        flushAndClear();
        return saved;
    }

    /** 요약 근거가 되는 주제를 붙인다. 없으면 요약 규칙이 근거 부족으로 묶음을 빼 시험이 다른 이유로 통과한다. */
    private void linkTopic(NewsArticle article) {
        Long topicId = links.ensureTopic("semiconductor", "반도체", true, LINK_RULE);
        links.replaceLinks(article.getId(), Set.of(), Map.of(topicId, "semiconductor"), LINK_RULE);
        flushAndClear();
    }

    private void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
    }

    @TestConfiguration
    static class TestBeans {

        @Bean
        NewsDigestQueryService.NewsQuerySettings newsQuerySettings() {
            return new NewsDigestQueryService.NewsQuerySettings(
                    NewsExposureMode.PUBLIC,
                    Duration.ofMinutes(30),
                    MAX_DIGESTS,
                    Duration.ofHours(48),
                    93);
        }

        @Bean
        com.ssafy.thorp.news.application.port.NewsSectorCatalog newsSectorCatalog() {
            return new com.ssafy.thorp.news.support.StubSectorCatalog(Set.of(SEMICONDUCTOR));
        }

        @Bean
        com.ssafy.thorp.news.application.port.NewsMetrics newsMetrics() {
            return new com.ssafy.thorp.news.support.RecordingNewsMetrics();
        }

        @Bean
        com.ssafy.thorp.news.application.port.NewsTopicCatalog newsTopicCatalog() {
            return new com.ssafy.thorp.news.infrastructure.config.RegistryNewsTopicCatalog(
                    new com.ssafy.thorp.news.infrastructure.config.NewsLinkRegistry(
                            LINK_RULE,
                            List.of(
                                    new com.ssafy.thorp.news.infrastructure.config.NewsLinkRegistry
                                            .Topic("semiconductor", "반도체", true, List.of())),
                            List.of()));
        }

        @Bean
        java.time.Clock newsClock() {
            return java.time.Clock.fixed(NOW, java.time.ZoneOffset.UTC);
        }
    }
}
