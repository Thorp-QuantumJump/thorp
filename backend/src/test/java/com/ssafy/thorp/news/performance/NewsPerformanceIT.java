package com.ssafy.thorp.news.performance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.ssafy.thorp.news.application.command.NewsDigestQuery;
import com.ssafy.thorp.news.application.port.GdeltNewsSource;
import com.ssafy.thorp.news.application.port.NewsCollectionLease;
import com.ssafy.thorp.news.application.port.NewsMetrics;
import com.ssafy.thorp.news.application.port.NewsSourceClient;
import com.ssafy.thorp.news.application.port.NewsTopicCatalog;
import com.ssafy.thorp.news.application.port.RssNewsSource;
import com.ssafy.thorp.news.application.repository.NewsArticleLinkRepository;
import com.ssafy.thorp.news.application.repository.NewsArticleRepository;
import com.ssafy.thorp.news.application.repository.NewsCollectionStateRepository;
import com.ssafy.thorp.news.application.repository.NewsDigestRepository;
import com.ssafy.thorp.news.application.repository.NewsPublisherRepository;
import com.ssafy.thorp.news.application.result.NewsDigestListResult;
import com.ssafy.thorp.news.application.result.NewsFetchResult;
import com.ssafy.thorp.news.application.service.NewsDigestQueryService;
import com.ssafy.thorp.news.application.service.NewsGroupingPolicy;
import com.ssafy.thorp.news.application.service.NewsIngestionService;
import com.ssafy.thorp.news.application.service.NewsRelevanceClassifier;
import com.ssafy.thorp.news.application.service.NewsRetryPolicy;
import com.ssafy.thorp.news.application.service.NewsSectorLinkPolicy;
import com.ssafy.thorp.news.application.service.NewsSummaryPolicy;
import com.ssafy.thorp.news.application.service.NewsUrlNormalizer;
import com.ssafy.thorp.news.domain.model.NewsPublisher;
import com.ssafy.thorp.news.domain.vo.PermissionStatus;
import com.ssafy.thorp.news.infrastructure.config.NewsPublisherRegistry;
import com.ssafy.thorp.news.infrastructure.scheduling.NewsCollectionScheduler;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 뉴스 조회와 수집 회차의 계획 상한을 실제로 재는 opt-in 성능 시험(T052).
 *
 * <p>노트북 성능과 Docker 상태에 따라 시간이 크게 달라져 기본 {@code build}에는 넣지 않는다. 실행할 때는 {@code
 * THORP_RUN_NEWS_PERFORMANCE=true}와 {@code THORP_REQUIRE_DOCKER=true}를 함께 설정한다. 전자는 이 시험을 켜고, 후자는
 * Docker가 없어 Testcontainers 시험이 조용히 건너뛰는 것을 막는다.
 *
 * <p>조회 fixture는 PostgreSQL의 {@code generate_series}로 100,000개 기사 행을 만든다. 애플리케이션 객체를 10만 번 저장하면
 * 측정하려는 조회보다 fixture 준비 시간이 훨씬 길어지고, JPA 배치 설정에 따라 결과가 흔들린다. SQL로 만드는 것은 측정 대상이 조회이기 때문이다.
 *
 * <p>수집 fixture는 외부망 대신 메모리 응답을 돌려주는 {@link NewsSourceClient}를 사용한다. 스케줄러가 동시에 due인 RSS 20개와 GDELT
 * 1개를 실제 ingestion 경계까지 모두 호출하는지 확인하면서 한 회차의 벽시계 시간을 잰다.
 */
@SpringBootTest(
        properties = {
            "thorp.news.collection-enabled=false",
            "spring.task.scheduling.enabled=false",
            "spring.rabbitmq.listener.simple.auto-startup=false",
            "spring.flyway.enabled=true",
            "spring.flyway.locations=classpath:db/migration",
            "spring.flyway.baseline-on-migrate=false",
            "spring.jpa.hibernate.ddl-auto=validate",
            "spring.datasource.hikari.maximum-pool-size=2",
            "spring.datasource.hikari.minimum-idle=0",
            "spring.mail.username=test@example.com",
            "spring.mail.password=test"
        })
@EnabledIfEnvironmentVariable(named = "THORP_RUN_NEWS_PERFORMANCE", matches = "true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Testcontainers(disabledWithoutDocker = true)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class NewsPerformanceIT {

    @Container @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    private static final int ARTICLE_COUNT = 100_000;
    private static final int WARM_UP_RUNS = 30;
    private static final int MEASURED_RUNS = 100;
    private static final Duration QUERY_P95_LIMIT = Duration.ofMillis(500);
    private static final Duration COLLECTION_CYCLE_LIMIT = Duration.ofMinutes(15);
    private static final Instant FIXED_NOW = Instant.parse("2026-09-16T03:00:00Z");

    @Autowired private JdbcTemplate jdbc;
    @Autowired private NewsDigestQueryService queryService;

    @BeforeAll
    void seedPerformanceFixture() {
        jdbc.execute(
                "TRUNCATE news_article_topic, news_article_sector, news_digest_article,"
                        + " news_digest, news_article, news_topic,"
                        + " news_publisher_permission_history, news_publisher,"
                        + " news_collection_state CASCADE");
        jdbc.update(
                "INSERT INTO news_publisher"
                        + " (id, publisher_key, display_name, language, registered_domain, rss_url,"
                        + " permission_status, permission_updated_at, enabled, created_at, updated_at)"
                        + " VALUES (?, 'performance', '성능 매체', 'ko', 'performance.example',"
                        + " 'https://performance.example/rss', 'ALLOWED', ?, TRUE, NOW(), NOW())",
                9_900_001L,
                java.sql.Timestamp.from(FIXED_NOW));
        jdbc.update(
                "INSERT INTO news_topic"
                        + " (id, topic_key, display_name, domestic_link, rule_version, created_at, updated_at)"
                        + " VALUES (9900001, 'markets', '시장', TRUE, 'news-link-v1', NOW(), NOW())");
        jdbc.update(
                "INSERT INTO news_collection_state"
                        + " (source_key, status, last_attempt_at, last_success_at, next_attempt_at,"
                        + " retry_count, created_at, updated_at)"
                        + " VALUES ('rss:performance', 'SUCCEEDED', ?, ?, ?, 0, NOW(), NOW())",
                java.sql.Timestamp.from(FIXED_NOW),
                java.sql.Timestamp.from(FIXED_NOW),
                java.sql.Timestamp.from(FIXED_NOW.plus(Duration.ofMinutes(15))));

        jdbc.execute(
                "INSERT INTO news_article"
                        + " (id, article_id, source_type, publisher_id, source_item_id, original_url,"
                        + " canonical_url, title, language, published_at, published_at_status,"
                        + " collected_at, duplicate_key, source_payload_hash, ingest_run_id,"
                        + " display_eligible, created_at, updated_at)"
                        + " SELECT 1000000 + g,"
                        + " translate(md5('article-' || g), '0123456789', 'ghijklmnop'),"
                        + " 'RSS', 9900001, 'fixture-' || g,"
                        + " 'https://performance.example/articles/' || g,"
                        + " 'https://performance.example/articles/' || g, '시장 기사 ' || g, 'ko',"
                        + " TIMESTAMPTZ '2026-09-16T03:00:00Z' - (g % 100) * INTERVAL '1 day',"
                        + " 'VERIFIED', TIMESTAMPTZ '2026-09-16T03:00:00Z', md5('duplicate-' || g),"
                        + " md5('payload-' || g), 'performance-fixture', TRUE, NOW(), NOW()"
                        + " FROM generate_series(1, 100000) AS g");
        jdbc.execute(
                "INSERT INTO news_digest"
                        + " (id, digest_id, representative_article_id, representative_title,"
                        + " factual_summary, first_published_at, latest_published_at,"
                        + " distinct_publisher_count, grouping_rule_version, importance_rule_version,"
                        + " created_at, updated_at)"
                        + " SELECT 2000000 + g,"
                        + " translate(md5('digest-' || g), '0123456789', 'ghijklmnop'),"
                        + " 1000000 + g, '시장 기사 ' || g,"
                        + " '성능 매체의 시장 기사 ' || g || ' 보도입니다.',"
                        + " TIMESTAMPTZ '2026-09-16T03:00:00Z' - (g % 100) * INTERVAL '1 day',"
                        + " TIMESTAMPTZ '2026-09-16T03:00:00Z' - (g % 100) * INTERVAL '1 day',"
                        + " 1, 'news-grouping-v1', 'news-importance-v1', NOW(), NOW()"
                        + " FROM generate_series(1, 100000) AS g WHERE g % 100 < 2");
        jdbc.execute(
                "INSERT INTO news_digest_article"
                        + " (digest_id, article_id, time_gap_seconds, title_similarity, shared_topic_key)"
                        + " SELECT 2000000 + g, 1000000 + g, 0, 1.0, 'markets'"
                        + " FROM generate_series(1, 100000) AS g WHERE g % 100 < 2");
        jdbc.execute(
                "INSERT INTO news_article_topic"
                        + " (article_id, topic_id, rule_version, match_evidence_key)"
                        + " SELECT 1000000 + g, 9900001, 'news-link-v1', 'markets'"
                        + " FROM generate_series(1, 100000) AS g WHERE g % 100 < 2");
        jdbc.execute("ANALYZE news_article");
        jdbc.execute("ANALYZE news_digest");
        jdbc.execute("ANALYZE news_digest_article");
        jdbc.execute("ANALYZE news_article_topic");

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM news_article", Integer.class))
                .isEqualTo(ARTICLE_COUNT);
    }

    @Test
    @DisplayName("10만 기사에서 현재 뉴스 조회 p95가 500ms 이하다")
    void digestQueryP95() {
        NewsDigestQuery query = NewsDigestQuery.current(null);
        for (int i = 0; i < WARM_UP_RUNS; i++) {
            queryService.query(query);
        }

        List<Long> durations = new ArrayList<>(MEASURED_RUNS);
        for (int i = 0; i < MEASURED_RUNS; i++) {
            long startedAt = System.nanoTime();
            NewsDigestListResult result = queryService.query(query);
            durations.add(System.nanoTime() - startedAt);
            assertThat(result.digests()).isNotEmpty().hasSizeLessThanOrEqualTo(3);
        }

        durations.sort(Comparator.naturalOrder());
        long p95Nanos = durations.get((int) Math.ceil(MEASURED_RUNS * 0.95) - 1);
        System.out.printf(
                java.util.Locale.ROOT,
                "NEWS_PERFORMANCE queryP95Ms=%.3f articles=%d warmups=%d measurements=%d%n",
                p95Nanos / 1_000_000.0,
                ARTICLE_COUNT,
                WARM_UP_RUNS,
                MEASURED_RUNS);
        assertThat(Duration.ofNanos(p95Nanos))
                .as("30회 준비 후 100회 측정한 현재 뉴스 조회 p95")
                .isLessThanOrEqualTo(QUERY_P95_LIMIT);
    }

    @Test
    @DisplayName("동시에 due인 RSS 20개와 GDELT 1개 수집 회차가 15분 안에 끝난다")
    void collectionCycle() {
        CollectionFixture fixture = collectionFixture();

        long startedAt = System.nanoTime();
        fixture.scheduler().collectDueSources();
        Duration elapsed = Duration.ofNanos(System.nanoTime() - startedAt);

        assertThat(fixture.sourceClient().rssRequests()).isEqualTo(20);
        assertThat(fixture.sourceClient().gdeltRequests()).isEqualTo(1);
        System.out.printf(
                java.util.Locale.ROOT,
                "NEWS_PERFORMANCE collectionCycleMs=%.3f rss=20 gdelt=1%n",
                elapsed.toNanos() / 1_000_000.0);
        assertThat(elapsed).isLessThanOrEqualTo(COLLECTION_CYCLE_LIMIT);
    }

    private static CollectionFixture collectionFixture() {
        List<NewsPublisher> publishers = new ArrayList<>();
        List<NewsPublisherRegistry.Publisher> registryEntries = new ArrayList<>();
        for (long i = 1; i <= 20; i++) {
            String key = "fixture-" + i;
            String domain = key + ".example";
            String rssUrl = "https://" + domain + "/rss";
            publishers.add(
                    NewsPublisher.restore(
                            i,
                            key,
                            "Fixture " + i,
                            "ko",
                            domain,
                            rssUrl,
                            PermissionStatus.PENDING,
                            null,
                            true,
                            null,
                            null));
            registryEntries.add(
                    new NewsPublisherRegistry.Publisher(
                            key, "Fixture " + i, "ko", domain, rssUrl, true, List.of("markets")));
        }

        NewsPublisherRepository publisherRepository = mock(NewsPublisherRepository.class);
        when(publisherRepository.findEnabled()).thenReturn(publishers);
        NewsCollectionStateRepository states = mock(NewsCollectionStateRepository.class);
        when(states.findBySourceKey(any())).thenReturn(Optional.empty());
        when(states.findAll()).thenReturn(List.of());
        when(states.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        NewsCollectionLease lease = mock(NewsCollectionLease.class);
        when(lease.tryAcquire(any(), any(), any()))
                .thenReturn(Optional.of(FIXED_NOW.plus(Duration.ofMinutes(15))));
        NewsTopicCatalog topics = mock(NewsTopicCatalog.class);
        when(topics.domesticLinkTopicKeys()).thenReturn(Set.of("markets"));
        NewsMetrics metrics = mock(NewsMetrics.class);
        FixtureSourceClient sourceClient = new FixtureSourceClient();
        TransactionTemplate transactions = mock(TransactionTemplate.class);
        when(transactions.execute(any()))
                .thenAnswer(
                        invocation -> {
                            TransactionCallback<?> callback = invocation.getArgument(0);
                            return callback.doInTransaction(mock(TransactionStatus.class));
                        });
        NewsIngestionService ingestion =
                new NewsIngestionService(
                        sourceClient,
                        mock(NewsArticleRepository.class),
                        mock(NewsArticleLinkRepository.class),
                        mock(NewsDigestRepository.class),
                        publisherRepository,
                        states,
                        mock(NewsUrlNormalizer.class),
                        mock(NewsRelevanceClassifier.class),
                        mock(NewsGroupingPolicy.class),
                        mock(NewsSummaryPolicy.class),
                        topics,
                        mock(NewsSectorLinkPolicy.class),
                        new NewsRetryPolicy(new java.util.Random(0)),
                        metrics,
                        transactions,
                        Clock.fixed(FIXED_NOW, ZoneOffset.UTC));

        NewsCollectionScheduler scheduler =
                new NewsCollectionScheduler(
                        publisherRepository,
                        states,
                        lease,
                        topics,
                        new NewsPublisherRegistry("performance-fixture-v1", registryEntries),
                        ingestion,
                        metrics,
                        Clock.fixed(FIXED_NOW, ZoneOffset.UTC));
        return new CollectionFixture(scheduler, sourceClient);
    }

    private record CollectionFixture(
            NewsCollectionScheduler scheduler, FixtureSourceClient sourceClient) {}

    private static final class FixtureSourceClient implements NewsSourceClient {
        private final AtomicInteger rssRequests = new AtomicInteger();
        private final AtomicInteger gdeltRequests = new AtomicInteger();

        @Override
        public NewsFetchResult fetch(RssNewsSource source) {
            rssRequests.incrementAndGet();
            return NewsFetchResult.complete(List.of(), null, null);
        }

        @Override
        public NewsFetchResult fetch(GdeltNewsSource source) {
            gdeltRequests.incrementAndGet();
            return NewsFetchResult.complete(List.of(), null, null);
        }

        int rssRequests() {
            return rssRequests.get();
        }

        int gdeltRequests() {
            return gdeltRequests.get();
        }
    }
}
