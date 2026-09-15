package com.ssafy.thorp.news.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.ssafy.thorp.news.application.port.GdeltNewsSource;
import com.ssafy.thorp.news.application.port.NewsSourceClient;
import com.ssafy.thorp.news.application.port.NewsTopicCatalog;
import com.ssafy.thorp.news.application.port.RssNewsSource;
import com.ssafy.thorp.news.application.repository.NewsArticleLinkRepository;
import com.ssafy.thorp.news.application.repository.NewsArticleRepository;
import com.ssafy.thorp.news.application.repository.NewsCollectionStateRepository;
import com.ssafy.thorp.news.application.repository.NewsDigestRepository;
import com.ssafy.thorp.news.application.repository.NewsPublisherRepository;
import com.ssafy.thorp.news.application.result.CollectedNewsItem;
import com.ssafy.thorp.news.application.result.NewsFetchResult;
import com.ssafy.thorp.news.domain.model.NewsArticle;
import com.ssafy.thorp.news.domain.model.NewsCollectionState;
import com.ssafy.thorp.news.domain.model.NewsDigest;
import com.ssafy.thorp.news.domain.model.NewsPublisher;
import com.ssafy.thorp.news.domain.vo.CanonicalNewsUrl;
import com.ssafy.thorp.news.domain.vo.NewsArticleId;
import com.ssafy.thorp.news.domain.vo.NewsDigestId;
import com.ssafy.thorp.news.domain.vo.NewsSourceType;
import com.ssafy.thorp.news.domain.vo.PermissionStatus;
import com.ssafy.thorp.news.infrastructure.config.NewsLinkRegistry;
import com.ssafy.thorp.news.infrastructure.config.RegistryNewsTopicCatalog;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 수집 회차를 끝까지 돌려 조각들이 실제로 이어지는지 본다.
 *
 * <p>규칙 단위 테스트는 각 조각이 맞다는 것만 말한다. 조각을 이어 붙인 자리가 비어 있어도 통과한다. 실제로 GDELT 기사가 관련성 필터에서 전량 폐기되던 결함과 해외
 * 기사가 국내 매체에 귀속되던 결함이 그렇게 숨어 있었다. 그래서 여기서는 저장소까지 가는 경로 전체를 확인한다.
 */
class NewsIngestionServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-10T06:00:00Z");

    private static final NewsLinkRegistry LINKS =
            new NewsLinkRegistry(
                    "news-link-test",
                    List.of(
                            new NewsLinkRegistry.Topic(
                                    "semiconductor", "반도체", true, List.of("sector-semi"))),
                    List.of(new NewsLinkRegistry.CategoryMapping("경제", "semiconductor")));

    private static final NewsPublisher RUN_PUBLISHER =
            NewsPublisher.restore(
                    1L,
                    "example-press",
                    "예시경제",
                    "ko",
                    "example.co.kr",
                    "https://rss.example.co.kr/economy.xml",
                    PermissionStatus.ALLOWED,
                    NOW,
                    true,
                    null,
                    null);

    /** 저장된 기사를 기억하는 저장소 대역. */
    private static final class ArticleStore implements NewsArticleRepository {
        private final List<NewsArticle> saved = new ArrayList<>();
        private final AtomicLong ids = new AtomicLong(100);

        @Override
        public NewsArticle upsertByCanonicalUrl(NewsArticle article) {
            NewsArticle stored =
                    NewsArticle.restore(
                            ids.incrementAndGet(),
                            article.getArticleId(),
                            article.getSourceType(),
                            article.getPublisherId(),
                            article.getSourceItemId(),
                            article.getOriginalUrl(),
                            article.getCanonicalUrl(),
                            article.getTitle(),
                            article.getLanguage(),
                            article.getPublishedAt(),
                            article.getPublishedAtStatus(),
                            article.getCollectedAt(),
                            article.getGdeltRecordedAt(),
                            article.getDuplicateKey(),
                            article.getSourcePayloadHash(),
                            article.getIngestRunId(),
                            article.isDisplayEligible());
            saved.add(stored);
            return stored;
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

        @Override
        public List<NewsArticle> findForExport(
                Instant from,
                Instant to,
                String sectorId,
                com.ssafy.thorp.news.application.command.NewsArticleExportQuery.Cursor after,
                int limit) {
            return List.of();
        }
    }

    /** 등록된 매체를 기억하는 저장소 대역. */
    private static final class PublisherStore implements NewsPublisherRepository {
        private final Map<String, NewsPublisher> byDomain = new HashMap<>();
        private final AtomicLong ids = new AtomicLong(1);

        PublisherStore() {
            this(true);
        }

        /** 매체 등록표가 비어 있는 상태를 만들 때 {@code false}를 준다. */
        PublisherStore(boolean seeded) {
            if (seeded) {
                byDomain.put(RUN_PUBLISHER.getRegisteredDomain(), RUN_PUBLISHER);
            }
        }

        @Override
        public NewsPublisher save(NewsPublisher publisher) {
            NewsPublisher stored =
                    NewsPublisher.restore(
                            ids.incrementAndGet(),
                            publisher.getPublisherKey(),
                            publisher.getDisplayName(),
                            publisher.getLanguage(),
                            publisher.getRegisteredDomain(),
                            publisher.getRssUrl(),
                            publisher.getPermissionStatus(),
                            publisher.getPermissionUpdatedAt(),
                            publisher.isEnabled(),
                            publisher.getEtag(),
                            publisher.getLastModified());
            byDomain.put(stored.getRegisteredDomain(), stored);
            return stored;
        }

        @Override
        public Optional<NewsPublisher> findById(Long id) {
            return byDomain.values().stream().filter(p -> p.getId().equals(id)).findFirst();
        }

        @Override
        public Optional<NewsPublisher> findByPublisherKey(String publisherKey) {
            return Optional.empty();
        }

        @Override
        public Optional<NewsPublisher> findByRegisteredDomain(String registeredDomain) {
            return Optional.ofNullable(byDomain.get(registeredDomain));
        }

        @Override
        public List<NewsPublisher> findEnabled() {
            return List.copyOf(byDomain.values());
        }

        @Override
        public List<NewsPublisher> findPubliclyVisible() {
            return List.of();
        }

        @Override
        public boolean existsPubliclyVisible() {
            return true;
        }
    }

    private static final class DigestStore implements NewsDigestRepository {
        private final List<NewsDigest> saved = new ArrayList<>();

        @Override
        public NewsDigest save(NewsDigest digest) {
            saved.add(digest);
            return digest;
        }

        @Override
        public Optional<NewsDigest> findByDigestId(NewsDigestId digestId) {
            return Optional.empty();
        }

        @Override
        public List<NewsDigest> findRecent(Instant since) {
            return List.of();
        }

        @Override
        public List<NewsDigest> findRecentBySector(Instant since, String sectorId) {
            return List.of();
        }

        @Override
        public List<NewsDigest> findVerifiedBetween(Instant from, Instant to, String sectorId) {
            return List.of();
        }

        @Override
        public List<NewsDigest> findByArticleIds(List<Long> articleIds) {
            return List.of();
        }

        @Override
        public void deleteByArticleIds(List<Long> articleIds) {
            // 이 시험은 저장 여부만 본다.
        }
    }

    private static final class StateStore implements NewsCollectionStateRepository {
        private NewsCollectionState last;

        @Override
        public NewsCollectionState save(NewsCollectionState state) {
            last = state;
            return state;
        }

        @Override
        public Optional<NewsCollectionState> findBySourceKey(String sourceKey) {
            return Optional.ofNullable(last);
        }

        @Override
        public List<NewsCollectionState> findDue(Instant now) {
            return List.of();
        }

        @Override
        public List<NewsCollectionState> findAll() {
            return last == null ? List.of() : List.of(last);
        }

        @Override
        public Optional<Instant> findLatestSuccessAt(boolean publicOnly) {
            return Optional.empty();
        }
    }

    private static final NewsArticleLinkRepository LINK_STORE =
            new NewsArticleLinkRepository() {
                @Override
                public Long ensureTopic(
                        String topicKey,
                        String displayName,
                        boolean domesticLink,
                        String ruleVersion) {
                    return 1L;
                }

                @Override
                public java.util.Map<Long, java.util.Set<String>> findSectorIdsByArticleIds(
                        java.util.Collection<Long> articleIds) {
                    return java.util.Map.of();
                }

                @Override
                public java.util.Map<Long, java.util.Set<String>> findTopicKeysByArticleIds(
                        java.util.Collection<Long> articleIds) {
                    return java.util.Map.of();
                }

                @Override
                public void replaceLinks(
                        Long articleId,
                        java.util.Set<String> sectorIds,
                        java.util.Map<Long, String> topicEvidenceByTopicId,
                        String ruleVersion) {
                    // 연결 저장은 이 시험의 관심사가 아니다.
                }
            };

    /** 트랜잭션 없이 그대로 실행하는 대역. */
    private static TransactionTemplate directTransactions() {
        return new TransactionTemplate() {
            @Override
            public <T> T execute(TransactionCallback<T> action) {
                return action.doInTransaction(new SimpleTransactionStatus());
            }
        };
    }

    private record Harness(
            NewsIngestionService service, ArticleStore articles, PublisherStore publishers) {}

    private static Harness harness(NewsSourceClient client) {
        return harness(client, true);
    }

    private static Harness harness(NewsSourceClient client, boolean seedPublisher) {
        ArticleStore articles = new ArticleStore();
        PublisherStore publishers = new PublisherStore(seedPublisher);
        NewsTopicCatalog topics = new RegistryNewsTopicCatalog(LINKS);
        NewsIngestionService service =
                new NewsIngestionService(
                        client,
                        articles,
                        LINK_STORE,
                        new DigestStore(),
                        publishers,
                        new StateStore(),
                        new NewsUrlNormalizer(),
                        new NewsRelevanceClassifier(topics),
                        new NewsGroupingPolicy(),
                        new NewsSummaryPolicy(topics),
                        topics,
                        new NewsSectorLinkPolicy(
                                new com.ssafy.thorp.news.support.StubSectorCatalog()),
                        new NewsRetryPolicy(new java.util.Random(0)),
                        new com.ssafy.thorp.news.support.RecordingNewsMetrics(),
                        directTransactions(),
                        Clock.fixed(NOW, ZoneOffset.UTC));
        return new Harness(service, articles, publishers);
    }

    private static CollectedNewsItem item(String url, String title, List<String> categories) {
        return new CollectedNewsItem(
                null,
                url,
                title,
                "ko",
                Instant.parse("2026-09-10T04:00:00Z"),
                null,
                categories,
                NewsSourceType.RSS);
    }

    @Test
    @DisplayName("GDELT 기사가 관련성 필터를 통과해 실제로 저장된다")
    void gdelt_기사가_저장된다() {
        // 조회에 쓴 주제가 기사에 실려 와야 한다. 비어 있으면 전량 폐기된다.
        NewsSourceClient client =
                new NewsSourceClient() {
                    @Override
                    public NewsFetchResult fetch(RssNewsSource source) {
                        return NewsFetchResult.complete(List.of(), null, null);
                    }

                    @Override
                    public NewsFetchResult fetch(GdeltNewsSource source) {
                        return NewsFetchResult.complete(
                                List.of(
                                        item(
                                                "https://press.overseas.example.com/m/1",
                                                "Korea chip exports data released",
                                                source.topicKeys())),
                                null,
                                null);
                    }
                };
        Harness harness = harness(client);

        NewsIngestionService.IngestOutcome outcome =
                harness.service()
                        .ingest(
                                new GdeltNewsSource(
                                        "gdelt:doc",
                                        NOW.minusSeconds(3600),
                                        NOW,
                                        List.of("semiconductor")));

        assertThat(outcome.collected()).as("한 건도 저장되지 않으면 보완 수집이 무의미하다").isEqualTo(1);
        assertThat(harness.articles().saved).hasSize(1);
    }

    @Test
    @DisplayName("배포 파일에서 온 기사는 저장되지만 표시 후보가 되지 않는다")
    void 배포_파일_표시_제외() {
        NewsSourceClient client =
                new NewsSourceClient() {
                    @Override
                    public NewsFetchResult fetch(RssNewsSource source) {
                        return NewsFetchResult.complete(List.of(), null, null);
                    }

                    @Override
                    public NewsFetchResult fetch(GdeltNewsSource source) {
                        return NewsFetchResult.complete(
                                List.of(
                                        new CollectedNewsItem(
                                                null,
                                                "https://press.overseas.example.com/m/1",
                                                "Korea chip exports data released",
                                                "und",
                                                null,
                                                Instant.parse("2026-09-10T04:15:00Z"),
                                                source.topicKeys(),
                                                NewsSourceType.GDELT_FILE)),
                                null,
                                null);
                    }
                };
        Harness harness = harness(client);

        NewsIngestionService.IngestOutcome outcome =
                harness.service()
                        .ingest(
                                new GdeltNewsSource(
                                        "gdelt:doc",
                                        NOW.minusSeconds(3600),
                                        NOW,
                                        List.of("semiconductor")));

        assertThat(outcome.collected()).as("연결·중복 판정 보조로 쓰려면 저장은 돼야 한다").isEqualTo(1);
        assertThat(harness.articles().saved.get(0).isDisplayEligible())
                .as("배포 레코드에는 매체가 발행한 시각이 없어 표시 후보가 되지 못한다")
                .isFalse();
        assertThat(outcome.digestsTouched()).as("표시할 수 없는 기사가 대표가 되면 묶음이 통째로 사라진다").isZero();
    }

    @Test
    @DisplayName("등록된 매체가 하나도 없어도 GDELT 회차가 돈다")
    void 매체_없이_gdelt_회차() {
        NewsSourceClient client =
                new NewsSourceClient() {
                    @Override
                    public NewsFetchResult fetch(RssNewsSource source) {
                        return NewsFetchResult.complete(List.of(), null, null);
                    }

                    @Override
                    public NewsFetchResult fetch(GdeltNewsSource source) {
                        return NewsFetchResult.complete(
                                List.of(
                                        item(
                                                "https://press.overseas.example.com/m/1",
                                                "Korea chip exports data released",
                                                source.topicKeys())),
                                null,
                                null);
                    }
                };
        Harness harness = harness(client, false);

        NewsIngestionService.IngestOutcome outcome =
                harness.service()
                        .ingest(
                                new GdeltNewsSource(
                                        "gdelt:doc",
                                        NOW.minusSeconds(3600),
                                        NOW,
                                        List.of("semiconductor")));

        assertThat(outcome.collected())
                .as("매체 등록은 공개 게이트와 별개라 등록표가 빈 동안에도 해외 보완 수집은 돌아야 한다")
                .isEqualTo(1);
        assertThat(harness.publishers().findByRegisteredDomain("press.overseas.example.com"))
                .as("회차가 돌면서 기사 주소의 매체를 미회신 상태로 등록한다")
                .isPresent();
    }

    @Test
    @DisplayName("해외 기사는 국내 매체가 아니라 자기 매체에 귀속된다")
    void 해외_기사_매체_귀속() {
        NewsSourceClient client =
                new NewsSourceClient() {
                    @Override
                    public NewsFetchResult fetch(RssNewsSource source) {
                        return NewsFetchResult.complete(List.of(), null, null);
                    }

                    @Override
                    public NewsFetchResult fetch(GdeltNewsSource source) {
                        return NewsFetchResult.complete(
                                List.of(
                                        item(
                                                "https://press.overseas.example.com/m/1",
                                                "Korea chip exports data released",
                                                source.topicKeys())),
                                null,
                                null);
                    }
                };
        Harness harness = harness(client);

        harness.service()
                .ingest(
                        new GdeltNewsSource(
                                "gdelt:doc",
                                NOW.minusSeconds(3600),
                                NOW,
                                List.of("semiconductor")));

        NewsArticle stored = harness.articles().saved.get(0);
        assertThat(stored.getPublisherId())
                .as("회차를 돌린 국내 매체에 붙으면 그 매체의 허가 상태를 물려받는다")
                .isNotEqualTo(RUN_PUBLISHER.getId());

        NewsPublisher owner = harness.publishers().findById(stored.getPublisherId()).orElseThrow();
        assertThat(owner.getRegisteredDomain()).isEqualTo("press.overseas.example.com");
        assertThat(owner.getPermissionStatus())
                .as("회신 전이므로 공개되지 않아야 한다")
                .isEqualTo(PermissionStatus.PENDING);
    }

    @Test
    @DisplayName("RSS 기사는 그 채널을 가진 매체에 귀속된다")
    void rss_기사_매체_귀속() {
        NewsSourceClient client =
                new NewsSourceClient() {
                    @Override
                    public NewsFetchResult fetch(RssNewsSource source) {
                        return NewsFetchResult.complete(
                                List.of(
                                        item(
                                                "https://rss.example.co.kr/economy/1",
                                                "반도체 수출 지표가 발표됐다",
                                                List.of("경제"))),
                                null,
                                null);
                    }

                    @Override
                    public NewsFetchResult fetch(GdeltNewsSource source) {
                        return NewsFetchResult.complete(List.of(), null, null);
                    }
                };
        Harness harness = harness(client);

        harness.service()
                .ingest(
                        new RssNewsSource(
                                1L,
                                "example-press",
                                "https://rss.example.co.kr/economy.xml",
                                null,
                                null),
                        RUN_PUBLISHER);

        assertThat(harness.articles().saved).hasSize(1);
        assertThat(harness.articles().saved.get(0).getPublisherId())
                .isEqualTo(RUN_PUBLISHER.getId());
    }
}
