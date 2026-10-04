package com.ssafy.thorp.news.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ssafy.thorp.common.config.JpaAuditingConfig;
import com.ssafy.thorp.news.application.command.NewsDigestQuery;
import com.ssafy.thorp.news.application.repository.NewsArticleRepository;
import com.ssafy.thorp.news.application.result.NewsDigestListResult;
import com.ssafy.thorp.news.domain.model.NewsArticle;
import com.ssafy.thorp.news.domain.model.NewsCollectionState;
import com.ssafy.thorp.news.domain.model.NewsDigest;
import com.ssafy.thorp.news.domain.model.NewsPublisher;
import com.ssafy.thorp.news.domain.model.NewsPublisherPermissionHistory;
import com.ssafy.thorp.news.domain.model.NewsRuleVersion;
import com.ssafy.thorp.news.domain.vo.CanonicalNewsUrl;
import com.ssafy.thorp.news.domain.vo.NewsAllowedField;
import com.ssafy.thorp.news.domain.vo.NewsCollectionStatus;
import com.ssafy.thorp.news.domain.vo.NewsSourceKey;
import com.ssafy.thorp.news.domain.vo.NewsSourceType;
import com.ssafy.thorp.news.domain.vo.PermissionStatus;
import com.ssafy.thorp.news.domain.vo.PublicationTimeStatus;
import com.ssafy.thorp.news.infrastructure.config.NewsProperties;
import com.ssafy.thorp.support.PostgresTestSupport;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 뉴스 영속 계층을 운영과 같은 PostgreSQL에서 검증한다.
 *
 * <p>여기서 확인하는 것은 스키마 존재가 아니라 <b>불변식이 DB에서도 지켜지는지</b>다. UNIQUE 키, 외부 식별자 충돌 거부, cascade, 허가 이력의
 * append-only, 검증 시각 조회, 허가 우선 투영, 수집 위치 트랜잭션성, 점유 만료 회수를 각각 단언한다.
 *
 * <p>Docker가 없으면 {@link PostgresTestSupport}가 통째로 skip한다. 결과의 {@code skipped}를 반드시 확인한다.
 */
@DataJpaTest
@Import({
    JpaAuditingConfig.class,
    NewsPublisherRepositoryImpl.class,
    NewsPublisherPermissionHistoryRepositoryImpl.class,
    NewsArticleRepositoryImpl.class,
    NewsDigestRepositoryImpl.class,
    NewsRuleVersionRepositoryImpl.class,
    NewsCollectionStateRepositoryImpl.class,
    NewsArticleLinkRepositoryImpl.class,
    DatabaseNewsCollectionLease.class,
    // 조회 경로는 JPQL 조인이 핵심이라 실제 DB에서만 검증된다. 컨텍스트를 새로 만들지 않고 여기 얹는 이유는
    // 이 저장소의 DB 테스트가 이미 공용 컨테이너의 커넥션을 거의 다 쓰고 있어서다.
    com.ssafy.thorp.news.application.service.NewsDigestQueryService.class,
    com.ssafy.thorp.news.application.service.NewsSummaryPolicy.class,
    com.ssafy.thorp.news.application.service.NewsImportancePolicy.class,
    com.ssafy.thorp.news.application.service.NewsFreshnessPolicy.class,
    com.ssafy.thorp.news.application.service.NewsSectorQueryPolicy.class
})
class NewsPersistenceIT extends PostgresTestSupport {

    private static final Instant NOW = Instant.parse("2026-09-10T06:00:00Z");
    private static final String GROUPING_RULE = "news-grouping-v1";
    private static final String IMPORTANCE_RULE = "news-importance-v1";
    private static final String LINK_RULE = "news-link-v1";

    /** spec 001이 소유하는 섹터 식별자. 형식도 001이 정한다. */
    private static final String SEMICONDUCTOR = "SEMICONDUCTOR";

    private static final String BATTERY = "SECONDARY_BATTERY";

    @TestConfiguration
    static class NewsTestConfig {
        @Bean
        NewsProperties newsProperties() {
            return new NewsProperties(
                    com.ssafy.thorp.news.domain.vo.NewsExposureMode.PUBLIC,
                    Duration.ofMinutes(30),
                    Duration.ofMinutes(15),
                    Duration.ofMinutes(15),
                    LocalDate.of(2026, 11, 7),
                    "Asia/Seoul",
                    93,
                    20,
                    3,
                    Duration.ofHours(48),
                    new NewsProperties.Http(
                            3,
                            Duration.ofSeconds(5),
                            Duration.ofSeconds(20),
                            10_485_760L,
                            Set.of("application/xml"),
                            Set.of("application/json"),
                            List.of("api.gdeltproject.org")));
        }

        /** 조회에 쓰는 설정. 노출 모드는 위 {@code NewsProperties}와 같은 PUBLIC이다. */
        @Bean
        com.ssafy.thorp.news.application.service.NewsDigestQueryService.NewsQuerySettings
                newsQuerySettings() {
            return new com.ssafy.thorp.news.application.service.NewsDigestQueryService
                    .NewsQuerySettings(
                    com.ssafy.thorp.news.domain.vo.NewsExposureMode.PUBLIC,
                    Duration.ofMinutes(30),
                    3,
                    Duration.ofHours(48),
                    93);
        }

        /** 001의 대응표 대역. 시험이 필요한 섹터만 등록된 것으로 둔다. */
        @Bean
        com.ssafy.thorp.news.application.port.NewsSectorCatalog newsSectorCatalog() {
            return new com.ssafy.thorp.news.support.StubSectorCatalog(
                    Set.of(SEMICONDUCTOR, BATTERY));
        }

        @Bean
        com.ssafy.thorp.news.application.port.NewsMetrics newsMetrics() {
            return new com.ssafy.thorp.news.support.RecordingNewsMetrics();
        }

        /** 주제 등록표 대역. 요약 규칙이 주제 표시명을 찾는 데만 쓴다. */
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

    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private TestEntityManager entityManager;
    @Autowired private NewsPublisherRepositoryImpl publisherRepository;
    @Autowired private NewsPublisherPermissionHistoryRepositoryImpl permissionHistoryRepository;
    @Autowired private NewsArticleRepositoryImpl articleRepository;
    @Autowired private NewsDigestRepositoryImpl digestRepository;
    @Autowired private NewsRuleVersionRepositoryImpl ruleVersionRepository;
    @Autowired private NewsCollectionStateRepositoryImpl collectionStateRepository;
    @Autowired private DatabaseNewsCollectionLease lease;
    @Autowired private NewsArticleLinkRepositoryImpl linkRepository;

    @Autowired private com.ssafy.thorp.news.application.service.NewsDigestQueryService queryService;

    private NewsPublisher savePublisher(String key, PermissionStatus status) {
        NewsPublisher publisher =
                publisherRepository.save(
                        NewsPublisher.register(
                                key,
                                key + " 신문",
                                "ko",
                                key + ".example.com",
                                "https://rss." + key + ".example.com/economy.xml",
                                true));
        if (status != PermissionStatus.PENDING) {
            publisher = publisherRepository.save(publisher.withPermission(status, NOW));
        }
        flushAndClear();
        return publisher;
    }

    private NewsArticle saveArticle(
            Long publisherId, String url, String title, Instant publishedAt) {
        CanonicalNewsUrl canonical = CanonicalNewsUrl.normalize(url);
        PublicationTimeStatus status = NewsArticle.classifyPublicationTime(publishedAt, NOW);
        NewsArticle saved =
                articleRepository.upsertByCanonicalUrl(
                        NewsArticle.collected(
                                NewsSourceType.RSS,
                                publisherId,
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

    private void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
    }

    @Test
    @DisplayName("같은 canonical URL을 다시 수집하면 행이 늘지 않고 외부 식별자와 최초 수집 시각이 유지된다")
    void canonicalUrlIsUnique() {
        NewsPublisher publisher = savePublisher("aa", PermissionStatus.ALLOWED);
        NewsArticle first =
                saveArticle(
                        publisher.getId(),
                        "https://rss.aa.example.com/1",
                        "첫 제목",
                        Instant.parse("2026-09-10T04:00:00Z"));

        NewsArticle second =
                saveArticle(
                        publisher.getId(),
                        "https://rss.aa.example.com/1?utm_source=x",
                        "고친 제목",
                        Instant.parse("2026-09-10T04:00:00Z"));

        assertThat(second.getId()).isEqualTo(first.getId());
        assertThat(second.getArticleId()).isEqualTo(first.getArticleId());
        assertThat(second.getCollectedAt()).isEqualTo(first.getCollectedAt());
        assertThat(second.getTitle()).isEqualTo("고친 제목");
        assertThat(countOf("news_article")).isEqualTo(1);
    }

    @Test
    @DisplayName("사업 키가 달라도 외부 식별자가 겹치면 DB가 두 번째 행을 거부한다")
    void articleIdCollisionIsRejectedByDatabase() {
        NewsPublisher publisher = savePublisher("bb", PermissionStatus.ALLOWED);
        NewsArticle existing =
                saveArticle(
                        publisher.getId(),
                        "https://rss.bb.example.com/1",
                        "원래 제목",
                        Instant.parse("2026-09-10T04:00:00Z"));

        // 외부 식별자는 canonical URL에서 유도되므로 정상 경로로는 충돌이 나지 않는다.
        // DB에 직접 넣어 UNIQUE 제약이 마지막 방어선으로 동작하는지 확인한다.
        assertThatThrownBy(
                        () ->
                                jdbcTemplate.update(
                                        "INSERT INTO news_article (id, article_id, source_type,"
                                                + " publisher_id, original_url, canonical_url, title,"
                                                + " language, published_at_status, collected_at,"
                                                + " duplicate_key, source_payload_hash, ingest_run_id,"
                                                + " display_eligible, created_at, updated_at) VALUES"
                                                + " (9001, ?, 'RSS', ?, ?, ?, '다른 기사', 'ko',"
                                                + " 'UNKNOWN', NOW(), 'dup', 'hash', 'run-2', TRUE,"
                                                + " NOW(), NOW())",
                                        existing.getArticleId().value(),
                                        publisher.getId(),
                                        "https://rss.bb.example.com/2",
                                        "https://rss.bb.example.com/2"))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);

        // 제약 위반이 트랜잭션을 중단시키므로 이 테스트에서는 더 조회하지 않는다.
        // 덮어쓰기가 없다는 것은 아래 저장소 경로 테스트가 확인한다.
    }

    @Test
    @DisplayName("이미 쓰인 외부 식별자로 새 기사를 저장하려 하면 덮어쓰지 않고 그 항목을 거부한다")
    void repositoryRejectsIdCollisionWithoutOverwrite() {
        NewsPublisher publisher = savePublisher("bx", PermissionStatus.ALLOWED);
        NewsArticle existing =
                saveArticle(
                        publisher.getId(),
                        "https://rss.bx.example.com/1",
                        "원래 제목",
                        Instant.parse("2026-09-10T04:00:00Z"));

        // 저장된 행의 canonical URL만 바꿔 두면, 같은 외부 식별자를 가진 새 기사가 들어오는 상황이 된다.
        jdbcTemplate.update(
                "UPDATE news_article SET canonical_url = ? WHERE id = ?",
                "https://rss.bx.example.com/moved",
                existing.getId());
        flushAndClear();

        NewsArticle incoming =
                NewsArticle.collected(
                        NewsSourceType.RSS,
                        publisher.getId(),
                        null,
                        "https://rss.bx.example.com/1",
                        CanonicalNewsUrl.normalize("https://rss.bx.example.com/1"),
                        "새 기사",
                        "ko",
                        null,
                        PublicationTimeStatus.UNKNOWN,
                        NOW,
                        null,
                        "dup",
                        "hash",
                        "run-2",
                        true);

        assertThatThrownBy(() -> articleRepository.upsertByCanonicalUrl(incoming))
                .isInstanceOf(NewsArticleRepository.NewsArticleIdCollisionException.class);

        assertThat(countOf("news_article")).isEqualTo(1);
        assertThat(articleRepository.findByArticleId(existing.getArticleId()))
                .get()
                .satisfies(a -> assertThat(a.getTitle()).isEqualTo("원래 제목"));
    }

    @Test
    @DisplayName("허가 이력은 쌓이고 매체 현재 상태는 같은 트랜잭션에서 함께 바뀐다")
    void permissionHistoryIsAppendOnly() {
        NewsPublisher publisher = savePublisher("cc", PermissionStatus.PENDING);

        permissionHistoryRepository.appendAndApply(
                NewsPublisherPermissionHistory.requested(publisher.getId(), NOW, "요청 메일"));
        permissionHistoryRepository.appendAndApply(
                NewsPublisherPermissionHistory.allowed(
                        publisher.getId(),
                        Set.of(NewsAllowedField.TITLE, NewsAllowedField.ORIGINAL_URL),
                        NOW.plusSeconds(60),
                        "회신 메일"));
        permissionHistoryRepository.appendAndApply(
                NewsPublisherPermissionHistory.denied(
                        publisher.getId(), NOW.plusSeconds(120), "철회 회신"));
        flushAndClear();

        List<NewsPublisherPermissionHistory> history =
                permissionHistoryRepository.findByPublisherId(publisher.getId());
        assertThat(history).hasSize(3);
        assertThat(history.get(0).permissionStatus()).isEqualTo(PermissionStatus.PENDING);
        assertThat(history.get(1).allowedFields())
                .containsExactlyInAnyOrder(NewsAllowedField.TITLE, NewsAllowedField.ORIGINAL_URL);

        assertThat(publisherRepository.findById(publisher.getId()))
                .get()
                .satisfies(
                        p -> {
                            assertThat(p.getPermissionStatus()).isEqualTo(PermissionStatus.DENIED);
                            assertThat(p.isPubliclyVisible()).isFalse();
                        });

        assertThat(permissionHistoryRepository.findLatestResponse(publisher.getId()))
                .get()
                .satisfies(
                        latest ->
                                assertThat(latest.permissionStatus())
                                        .isEqualTo(PermissionStatus.DENIED));
    }

    @Test
    @DisplayName("발행 시각이 확인된 기사만 구간 조회에 나온다")
    void verifiedTimeQueryExcludesUnknown() {
        NewsPublisher publisher = savePublisher("dd", PermissionStatus.ALLOWED);
        saveArticle(
                publisher.getId(),
                "https://rss.dd.example.com/1",
                "확인된 기사",
                Instant.parse("2026-09-10T04:00:00Z"));
        saveArticle(publisher.getId(), "https://rss.dd.example.com/2", "미확인 기사", null);

        List<NewsArticle> found =
                articleRepository.findVerifiedBetween(
                        Instant.parse("2026-09-10T00:00:00Z"),
                        Instant.parse("2026-09-10T05:00:00Z"));

        assertThat(found).hasSize(1);
        assertThat(found.get(0).getTitle()).isEqualTo("확인된 기사");
        assertThat(found.get(0).getPublishedAtStatus()).isEqualTo(PublicationTimeStatus.VERIFIED);
    }

    @Test
    @DisplayName("묶음은 전체 멤버로 저장되고 공개 투영은 허가된 기사만으로 다시 계산된다")
    void permissionFirstProjection() {
        NewsPublisher allowed = savePublisher("ee", PermissionStatus.ALLOWED);
        NewsPublisher denied = savePublisher("ff", PermissionStatus.DENIED);

        NewsArticle deniedArticle =
                saveArticle(
                        denied.getId(),
                        "https://rss.ff.example.com/1",
                        "불허 매체 제목",
                        Instant.parse("2026-09-10T04:00:00Z"));
        NewsArticle allowedArticle =
                saveArticle(
                        allowed.getId(),
                        "https://rss.ee.example.com/1",
                        "허가 매체 제목",
                        Instant.parse("2026-09-10T04:30:00Z"));

        digestRepository.save(
                NewsDigest.of(
                        List.of(deniedArticle, allowedArticle),
                        "요약",
                        GROUPING_RULE,
                        IMPORTANCE_RULE));
        flushAndClear();

        List<NewsDigest> loaded =
                digestRepository.findRecent(Instant.parse("2026-09-01T00:00:00Z"));
        assertThat(loaded).hasSize(1);
        NewsDigest digest = loaded.get(0);

        assertThat(digest.getArticles()).as("저장된 묶음은 전체 멤버를 담는다").hasSize(2);
        assertThat(digest.getRepresentativeTitle()).isEqualTo("불허 매체 제목");

        Optional<NewsDigest.Projection> projection =
                digest.projectFor(
                        a -> a.getPublisherId().equals(allowed.getId()),
                        (representative, members) ->
                                Optional.of(representative.getTitle() + " 보도"));

        assertThat(projection).isPresent();
        assertThat(projection.get().representativeTitle()).isEqualTo("허가 매체 제목");
        assertThat(projection.get().factualSummary()).doesNotContain("불허 매체 제목");
        assertThat(projection.get().distinctPublisherCount()).isEqualTo(1);

        assertThat(
                        digestRepository
                                .findRecent(Instant.parse("2026-09-01T00:00:00Z"))
                                .get(0)
                                .getRepresentativeTitle())
                .as("허가 변경이 저장된 묶음을 덮어쓰지 않는다")
                .isEqualTo("불허 매체 제목");
    }

    @Test
    @DisplayName("기사를 지우면 묶음 멤버십도 함께 사라진다")
    void cascadeRemovesMembership() {
        NewsPublisher publisher = savePublisher("gg", PermissionStatus.ALLOWED);
        NewsArticle article =
                saveArticle(
                        publisher.getId(),
                        "https://rss.gg.example.com/1",
                        "제목",
                        Instant.parse("2026-09-10T04:00:00Z"));
        digestRepository.save(
                NewsDigest.of(List.of(article), "요약", GROUPING_RULE, IMPORTANCE_RULE));
        flushAndClear();

        assertThat(countOf("news_digest_article")).isEqualTo(1);

        jdbcTemplate.update("DELETE FROM news_article WHERE id = ?", article.getId());

        assertThat(countOf("news_digest_article")).isZero();
    }

    @Test
    @DisplayName("매체를 지우면 기사와 허가 이력이 함께 사라진다")
    void cascadeRemovesPublisherDependents() {
        NewsPublisher publisher = savePublisher("hh", PermissionStatus.ALLOWED);
        saveArticle(
                publisher.getId(),
                "https://rss.hh.example.com/1",
                "제목",
                Instant.parse("2026-09-10T04:00:00Z"));
        permissionHistoryRepository.appendAndApply(
                NewsPublisherPermissionHistory.requested(publisher.getId(), NOW, "요청"));
        flushAndClear();

        jdbcTemplate.update("DELETE FROM news_publisher WHERE id = ?", publisher.getId());

        assertThat(countOf("news_article")).isZero();
        assertThat(countOf("news_publisher_permission_history")).isZero();
    }

    @Test
    @DisplayName("성공은 수집 위치를 전진시키고 부분 수집은 전진시키지 않는다")
    void cursorAdvancesOnlyOnSuccess() {
        NewsCollectionState initial =
                collectionStateRepository.save(NewsCollectionState.idle("rss:ii"));
        flushAndClear();

        NewsCollectionState claimed = initial.claimed("instance-1", NOW, Duration.ofMinutes(15));
        collectionStateRepository.save(
                claimed.succeeded("cursor-1", NOW, NOW.plus(Duration.ofMinutes(15))));
        flushAndClear();

        NewsCollectionState afterSuccess =
                collectionStateRepository.findBySourceKey("rss:ii").orElseThrow();
        assertThat(afterSuccess.getCursor()).isEqualTo("cursor-1");
        assertThat(afterSuccess.getLastSuccessAt()).isEqualTo(NOW);

        Instant later = NOW.plus(Duration.ofMinutes(30));
        collectionStateRepository.save(
                afterSuccess
                        .claimed("instance-1", later, Duration.ofMinutes(15))
                        .incomplete(later, later.plus(Duration.ofMinutes(15))));
        flushAndClear();

        NewsCollectionState afterIncomplete =
                collectionStateRepository.findBySourceKey("rss:ii").orElseThrow();
        assertThat(afterIncomplete.getStatus()).isEqualTo(NewsCollectionStatus.INCOMPLETE);
        assertThat(afterIncomplete.getCursor()).as("같은 구간을 다시 시도한다").isEqualTo("cursor-1");
        assertThat(afterIncomplete.getLastSuccessAt()).as("부분 수집을 완전 수집으로 기록하지 않는다").isEqualTo(NOW);
        assertThat(afterIncomplete.getErrorCategory())
                .isEqualTo(NewsCollectionState.RESULT_CAP_REACHED);
    }

    @Test
    @DisplayName("점유는 한 인스턴스만 잡고 만료되면 다른 인스턴스가 회수한다")
    void leaseIsExclusiveAndExpires() {
        collectionStateRepository.save(NewsCollectionState.idle("rss:jj"));
        flushAndClear();

        assertThat(lease.tryAcquire("rss:jj", "instance-1", NOW)).isPresent();
        assertThat(lease.tryAcquire("rss:jj", "instance-2", NOW))
                .as("유효한 점유가 있으면 다른 인스턴스는 잡지 못한다")
                .isEmpty();

        Instant afterExpiry = NOW.plus(Duration.ofMinutes(16));
        assertThat(lease.tryAcquire("rss:jj", "instance-2", afterExpiry))
                .as("만료된 점유는 회수된다")
                .isPresent();
    }

    @Test
    @DisplayName("정리 작업도 수집 뉴스원과 같은 점유 테이블을 쓴다")
    void retentionCleanupSharesLeaseTable() {
        assertThat(
                        collectionStateRepository.findBySourceKey(
                                NewsCollectionState.RETENTION_CLEANUP_SOURCE_KEY))
                .as("V3가 정리 작업 행을 seed한다")
                .isPresent();

        assertThat(lease.tryAcquire(NewsCollectionState.RETENTION_CLEANUP_SOURCE_KEY, "i-1", NOW))
                .isPresent();
        assertThat(lease.tryAcquire(NewsCollectionState.RETENTION_CLEANUP_SOURCE_KEY, "i-2", NOW))
                .isEmpty();
    }

    @Test
    @DisplayName("마지막 갱신 시각은 성공 이력이 없으면 비어 있다")
    void latestSuccessIsEmptyBeforeFirstRun() {
        assertThat(collectionStateRepository.findLatestSuccessAt(false)).isEmpty();

        collectionStateRepository.save(
                NewsCollectionState.idle("rss:kk")
                        .claimed("i-1", NOW, Duration.ofMinutes(15))
                        .succeeded("c", NOW, NOW.plus(Duration.ofMinutes(15))));
        flushAndClear();

        assertThat(collectionStateRepository.findLatestSuccessAt(false)).contains(NOW);
    }

    @Test
    @DisplayName("공개 조회의 마지막 갱신 시각은 허가된 매체의 뉴스원 성공만 센다")
    void latestSuccessForPublicUsesSharedSourceKeyFormat() {
        NewsPublisher allowed = savePublisher("ll", PermissionStatus.ALLOWED);
        NewsPublisher denied = savePublisher("mm", PermissionStatus.DENIED);

        // 수집 키는 NewsSourceKey가 만든다. 질의도 같은 접두를 인자로 받으므로 형식이 어긋날 수 없다.
        collectionStateRepository.save(
                NewsCollectionState.idle(NewsSourceKey.rss(denied.getPublisherKey()))
                        .claimed("i-1", NOW, Duration.ofMinutes(15))
                        .succeeded("c", NOW.plusSeconds(600), NOW.plus(Duration.ofMinutes(15))));
        flushAndClear();

        assertThat(collectionStateRepository.findLatestSuccessAt(true))
                .as("불허 매체의 신선도는 공개 화면에 새지 않는다")
                .isEmpty();

        collectionStateRepository.save(
                NewsCollectionState.idle(NewsSourceKey.rss(allowed.getPublisherKey()))
                        .claimed("i-1", NOW, Duration.ofMinutes(15))
                        .succeeded("c", NOW, NOW.plus(Duration.ofMinutes(15))));
        flushAndClear();

        assertThat(collectionStateRepository.findLatestSuccessAt(true))
                .as("허가된 매체의 수집 성공은 마지막 갱신 시각이 된다")
                .contains(NOW);
    }

    @Test
    @DisplayName("기존 묶음에 기사가 늘어도 멤버십 재저장이 기본키와 충돌하지 않는다")
    void digestCanBeResavedWithAddedMember() {
        NewsPublisher publisher = savePublisher("nn", PermissionStatus.ALLOWED);
        NewsArticle first =
                saveArticle(
                        publisher.getId(),
                        "https://rss.nn.example.com/1",
                        "첫 보도",
                        Instant.parse("2026-09-10T04:00:00Z"));
        NewsArticle second =
                saveArticle(
                        publisher.getId(),
                        "https://rss.nn.example.com/2",
                        "둘째 보도",
                        Instant.parse("2026-09-10T04:10:00Z"));

        digestRepository.save(
                NewsDigest.of(List.of(first, second), "요약", GROUPING_RULE, IMPORTANCE_RULE));
        flushAndClear();

        NewsArticle third =
                saveArticle(
                        publisher.getId(),
                        "https://rss.nn.example.com/3",
                        "셋째 보도",
                        Instant.parse("2026-09-10T04:20:00Z"));

        // 같은 대표 기사라 digestId가 같다. 기존 멤버 둘을 그대로 다시 넣는 경로다.
        digestRepository.save(
                NewsDigest.of(List.of(first, second, third), "요약", GROUPING_RULE, IMPORTANCE_RULE));
        flushAndClear();

        assertThat(countOf("news_digest")).as("같은 묶음이 하나로 유지된다").isEqualTo(1);
        assertThat(countOf("news_digest_article")).isEqualTo(3);

        List<NewsDigest> loaded =
                digestRepository.findRecent(Instant.parse("2026-09-01T00:00:00Z"));
        assertThat(loaded).hasSize(1);
        assertThat(loaded.get(0).getArticles()).hasSize(3);
    }

    @Test
    @DisplayName("발행 시각이 확인되지 않은 묶음은 확인된 묶음보다 뒤에 온다")
    void unverifiedDigestsSortAfterVerifiedOnes() {
        NewsPublisher publisher = savePublisher("oo", PermissionStatus.ALLOWED);
        NewsArticle unknown =
                saveArticle(publisher.getId(), "https://rss.oo.example.com/1", "시각 미확인 보도", null);
        NewsArticle verified =
                saveArticle(
                        publisher.getId(),
                        "https://rss.oo.example.com/2",
                        "시각 확인 보도",
                        Instant.parse("2026-09-10T04:00:00Z"));

        digestRepository.save(
                NewsDigest.of(List.of(unknown), "요약", GROUPING_RULE, IMPORTANCE_RULE));
        digestRepository.save(
                NewsDigest.of(List.of(verified), "요약", GROUPING_RULE, IMPORTANCE_RULE));
        flushAndClear();

        List<NewsDigest> ordered =
                digestRepository.findRecent(Instant.parse("2026-09-01T00:00:00Z"));

        assertThat(ordered).hasSize(2);
        assertThat(ordered.get(0).getRepresentativeTitle())
                .as("검증된 발행 시각이 있는 묶음이 앞선다")
                .isEqualTo("시각 확인 보도");
        assertThat(ordered.get(1).getRepresentativeTitle()).isEqualTo("시각 미확인 보도");
    }

    @Test
    @DisplayName("그 시점에 유효했던 묶음 규칙을 읽는다")
    void seededRuleVersionsAreReadable() {
        assertThat(ruleVersionRepository.findAll())
                .extracting(NewsRuleVersion::ruleVersion)
                .contains("news-grouping-v1", "news-grouping-v2");

        // NOW는 2026-09-10이고 v2는 09-16부터다. 그때 판정에 쓰인 것은 v1이며 그 사실이 남아 있어야
        // 당시에 만들어진 묶음을 재현할 수 있다(원칙 VIII).
        assertThat(ruleVersionRepository.findEffective(NewsRuleVersion.RuleType.GROUPING, NOW))
                .get()
                .satisfies(
                        rule -> {
                            assertThat(rule.ruleVersion()).isEqualTo(GROUPING_RULE);
                            assertThat(rule.definition()).containsKey("titleJaccardThreshold");
                        });

        assertThat(
                        ruleVersionRepository.findEffective(
                                NewsRuleVersion.RuleType.GROUPING,
                                Instant.parse("2026-09-20T00:00:00Z")))
                .get()
                .satisfies(
                        rule -> {
                            assertThat(rule.ruleVersion()).isEqualTo("news-grouping-v2");
                            assertThat(rule.definition())
                                    .containsKey("titleBigramJaccardThreshold");
                        });
    }

    /**
     * 조회 경로를 실제 DB에서 확인한다(spec FR-005·FR-006, T025·T030·T032).
     *
     * <p>섹터 필터와 과거 구간 필터는 JPQL 조인이 전부라 대역으로는 확인할 수 없다. 조인 조건이 틀리면 대역에서는 통과하고 운영에서만 빈 화면이 된다.
     */
    /** 허가 매체 하나와 그 매체의 기사로 만든 묶음. 섹터 연결까지 붙인다. */
    private NewsDigest seedDigest(
            String key, String url, String title, Instant publishedAt, String sectorId) {
        return seedDigestLinkedTo(
                key, url, title, publishedAt, sectorId == null ? Set.of() : Set.of(sectorId));
    }

    /** 기사 하나를 섹터 여럿에 연결한 묶음. 섹터 여럿 조회가 같은 묶음을 두 번 내지 않는지 볼 때 쓴다. */
    private NewsDigest seedDigestLinkedTo(
            String key, String url, String title, Instant publishedAt, Set<String> sectorIds) {
        NewsPublisher publisher = savePublisher(key, PermissionStatus.ALLOWED);
        NewsArticle article = saveArticle(publisher.getId(), url, title, publishedAt);
        Long topicId = linkRepository.ensureTopic("semiconductor", "반도체", true, LINK_RULE);
        linkRepository.replaceLinks(
                article.getId(), sectorIds, java.util.Map.of(topicId, "semiconductor"), LINK_RULE);
        NewsDigest digest =
                digestRepository.save(
                        NewsDigest.of(List.of(article), "요약", GROUPING_RULE, IMPORTANCE_RULE));
        flushAndClear();
        return digest;
    }

    @Test
    @DisplayName("섹터를 지정하면 그 섹터에 연결된 묶음만 돌려준다")
    void 섹터_필터() {
        seedDigest(
                "semi",
                "https://semi.example.com/1",
                "반도체 수출 지표",
                NOW.minusSeconds(3600),
                SEMICONDUCTOR);
        seedDigest(
                "batt",
                "https://batt.example.com/1",
                "이차전지 수출 지표",
                NOW.minusSeconds(3600),
                BATTERY);

        NewsDigestListResult result = queryService.query(NewsDigestQuery.current(SEMICONDUCTOR));

        assertThat(result.digests()).as("조인 조건이 틀리면 대역에서는 통과하고 운영에서만 다른 섹터가 섞인다").hasSize(1);
        assertThat(result.digests().get(0).sectors()).containsExactly(SEMICONDUCTOR);
        assertThat(result.sectorId()).isEqualTo(SEMICONDUCTOR);
    }

    @Test
    @DisplayName("섹터를 여럿 지정하면 그중 하나 이상에 연결된 묶음을 한 번의 질의로 모은다 (spec FR-001 ③)")
    void 섹터_여럿_필터() {
        seedDigest(
                "semi",
                "https://semi.example.com/many",
                "반도체 수출 지표",
                NOW.minusSeconds(3600),
                SEMICONDUCTOR);
        seedDigest(
                "batt",
                "https://batt.example.com/many",
                "이차전지 수출 지표",
                NOW.minusSeconds(7200),
                BATTERY);
        seedDigest(
                "none",
                "https://none.example.com/many",
                "섹터 연결이 없는 기사",
                NOW.minusSeconds(1800),
                null);
        seedDigestLinkedTo(
                "both",
                "https://both.example.com/many",
                "두 섹터에 걸친 수출 지표",
                NOW.minusSeconds(5400),
                Set.of(SEMICONDUCTOR, BATTERY));

        NewsDigestListResult result =
                queryService.query(List.of(SEMICONDUCTOR, BATTERY), null, null, 10);

        assertThat(result.digests())
                .as("IN 조건이 틀리면 연결 없는 묶음이 섞이고, DISTINCT가 빠지면 두 섹터에 걸친 묶음이 두 번 나온다")
                .extracting(NewsDigestListResult.Digest::representativeTitle)
                .containsExactly("반도체 수출 지표", "두 섹터에 걸친 수출 지표", "이차전지 수출 지표");
        assertThat(result.sectorId()).as("섹터가 여럿이면 응답의 sectorId는 비운다").isNull();
    }

    @Test
    @DisplayName("제목 키워드 다시 연결은 옛 버전 연결을 남기고 없는 연결만 더하며, 두 번 돌려도 같다")
    void 제목_다시_연결() {
        NewsDigest digest =
                seedDigest(
                        "semi",
                        "https://semi.example.com/relink",
                        "HBM 공급 늘린다",
                        NOW.minusSeconds(3600),
                        null);
        Long articleId = digest.getArticles().get(0).getId();
        assertThat(queryService.query(NewsDigestQuery.current(SEMICONDUCTOR)).digests())
                .as("v1에는 섹터 연결이 없었다")
                .isEmpty();

        Long titleTopic =
                linkRepository.ensureTopic("sector-semiconductor", "반도체 업종", false, "news-link-v2");
        for (int run = 0; run < 2; run++) {
            linkRepository.addLinks(
                    articleId,
                    Set.of(SEMICONDUCTOR),
                    java.util.Map.of(titleTopic, "title:hbm"),
                    "news-link-v2");
            flushAndClear();
        }

        assertThat(queryService.query(NewsDigestQuery.current(SEMICONDUCTOR)).digests()).hasSize(1);
        assertThat(linkRepository.findTopicKeysByArticleIds(List.of(articleId)).get(articleId))
                .as("v1 주제 연결은 지우지 않는다")
                .containsExactlyInAnyOrder("semiconductor", "sector-semiconductor");
    }

    @Test
    @DisplayName("001에 등록되지 않은 섹터는 거부한다")
    void 미등록_섹터() {
        assertThatThrownBy(() -> queryService.query(NewsDigestQuery.current("NOT_REGISTERED")))
                .isInstanceOf(com.ssafy.thorp.news.application.exception.NewsQueryException.class);
    }

    @Test
    @DisplayName("연결된 자료가 없는 섹터는 오류가 아니라 빈 화면이다")
    void 빈_섹터() {
        seedDigest(
                "semi",
                "https://semi.example.com/1",
                "반도체 수출 지표",
                NOW.minusSeconds(3600),
                SEMICONDUCTOR);

        NewsDigestListResult result = queryService.query(NewsDigestQuery.current(BATTERY));

        assertThat(result.digests()).isEmpty();
        assertThat(result.state())
                .as("개수를 맞추려고 다른 섹터의 뉴스를 끌어오지 않는다")
                .isEqualTo(com.ssafy.thorp.news.domain.vo.NewsDigestState.EMPTY);
    }

    @Test
    @DisplayName("과거 조회는 발행 시각이 확인된 기사만 본다")
    void 미확인_시각_제외() {
        seedDigest("unknown", "https://unknown.example.com/1", "시각 미확인 기사", null, SEMICONDUCTOR);

        NewsDigestListResult result =
                queryService.query(new NewsDigestQuery(null, NOW.minus(Duration.ofDays(7)), NOW));

        assertThat(result.digests()).as("그 기사가 정말 그 구간에 나왔는지 말할 수 없다(원칙 V)").isEmpty();
    }

    @Test
    @DisplayName("과거 조회는 요청 구간 밖의 기사를 빼고 본다")
    void 구간_밖_제외() {
        Instant longAgo = NOW.minus(Duration.ofDays(30));
        seedDigest("old", "https://old.example.com/1", "오래된 기사", longAgo, SEMICONDUCTOR);

        NewsDigestListResult inRange =
                queryService.query(
                        new NewsDigestQuery(
                                null,
                                longAgo.minus(Duration.ofDays(1)),
                                longAgo.plus(Duration.ofDays(1))));
        NewsDigestListResult outOfRange =
                queryService.query(new NewsDigestQuery(null, NOW.minus(Duration.ofDays(2)), NOW));

        assertThat(inRange.digests()).hasSize(1);
        assertThat(outOfRange.digests()).isEmpty();
        assertThat(outOfRange.context()).isEqualTo(NewsDigestQuery.Context.HISTORICAL);
    }

    @Test
    @DisplayName("회신하지 않은 매체의 기사는 공개 조회에 나오지 않는다")
    void 미허가_매체_제외() {
        NewsPublisher pending = savePublisher("pending", PermissionStatus.PENDING);
        NewsArticle article =
                saveArticle(
                        pending.getId(),
                        "https://pending.example.com/1",
                        "회신 전 매체 기사",
                        NOW.minusSeconds(600));
        digestRepository.save(
                NewsDigest.of(List.of(article), "요약", GROUPING_RULE, IMPORTANCE_RULE));
        flushAndClear();

        NewsDigestListResult result = queryService.query(NewsDigestQuery.current(null));

        assertThat(result.digests()).as("회신이 없는 매체의 항목은 공개 환경에 표시하지 않는다(spec FR-016)").isEmpty();
    }

    @Test
    @DisplayName("늦게 입력한 과거 회신이 현재 허가 상태를 덮지 않는다")
    void 과거_회신은_현재_상태를_덮지_않는다() {
        NewsPublisher publisher = savePublisher("late", PermissionStatus.PENDING);

        permissionHistoryRepository.appendAndApply(
                NewsPublisherPermissionHistory.allowed(
                        publisher.getId(), Set.of(NewsAllowedField.TITLE), NOW, "9월 10일 허가 회신"));
        // 메일함에 묻혀 있던 옛 불허 회신을 뒤늦게 입력한다.
        permissionHistoryRepository.appendAndApply(
                NewsPublisherPermissionHistory.denied(
                        publisher.getId(), NOW.minus(Duration.ofDays(5)), "9월 5일 불허 회신"));
        flushAndClear();

        assertThat(permissionHistoryRepository.findByPublisherId(publisher.getId()))
                .as("이력은 늘 남긴다. 늦게 넣었다고 근거가 사라지면 안 된다")
                .hasSize(2);
        assertThat(publisherRepository.findById(publisher.getId()))
                .get()
                .satisfies(
                        p ->
                                assertThat(p.getPermissionStatus())
                                        .as("이미 받아 둔 최신 회신이 옛 회신에 조용히 뒤집히면 공개 여부가 바뀐다")
                                        .isEqualTo(PermissionStatus.ALLOWED));
    }

    @Test
    @DisplayName("같은 시각의 사건은 나중에 기록한 쪽이 현재 상태가 된다")
    void 같은_시각이면_나중_기록() {
        NewsPublisher publisher = savePublisher("tie", PermissionStatus.PENDING);

        permissionHistoryRepository.appendAndApply(
                NewsPublisherPermissionHistory.allowed(
                        publisher.getId(), Set.of(NewsAllowedField.TITLE), NOW, "허가"));
        permissionHistoryRepository.appendAndApply(
                NewsPublisherPermissionHistory.denied(publisher.getId(), NOW, "불허"));
        flushAndClear();

        assertThat(publisherRepository.findById(publisher.getId()))
                .get()
                .satisfies(
                        p ->
                                assertThat(p.getPermissionStatus())
                                        .as("사람이 마지막으로 판단한 것을 따른다")
                                        .isEqualTo(PermissionStatus.DENIED));
    }

    @Test
    @DisplayName("발행 시각 미확인 묶음도 현재 조회 창 밖이면 빠진다")
    void 미확인_묶음도_창을_지킨다() {
        // 수집 시각은 고정 시계의 NOW다. 창(48시간)보다 훨씬 오래된 시각으로 옮겨 둔다.
        NewsPublisher publisher = savePublisher("old", PermissionStatus.ALLOWED);
        NewsArticle article =
                saveArticle(publisher.getId(), "https://old.example.com/1", "시각 미확인 기사", null);
        linkTopic(article);
        digestRepository.save(
                NewsDigest.of(List.of(article), "요약", GROUPING_RULE, IMPORTANCE_RULE));
        flushAndClear();
        jdbcTemplate.update(
                "UPDATE news_article SET collected_at = ? WHERE id = ?",
                java.sql.Timestamp.from(NOW.minus(Duration.ofDays(90))),
                article.getId());

        NewsDigestListResult result = queryService.query(NewsDigestQuery.current(null));

        assertThat(result.digests()).as("몇 달 전 미확인 기사가 '현재 뉴스'로 올라오면 안 된다").isEmpty();
    }

    @Test
    @DisplayName("발행 시각 미확인 묶음도 창 안이면 남는다")
    void 창_안의_미확인_묶음() {
        NewsPublisher publisher = savePublisher("fresh", PermissionStatus.ALLOWED);
        NewsArticle article =
                saveArticle(publisher.getId(), "https://fresh.example.com/1", "시각 미확인 기사", null);
        linkTopic(article);
        digestRepository.save(
                NewsDigest.of(List.of(article), "요약", GROUPING_RULE, IMPORTANCE_RULE));
        flushAndClear();

        NewsDigestListResult result = queryService.query(NewsDigestQuery.current(null));

        assertThat(result.digests()).as("창을 좁히느라 방금 들어온 미확인 기사까지 버리면 안 된다").hasSize(1);
    }

    /**
     * 요약 근거가 되는 주제를 붙인다.
     *
     * <p>이것이 없으면 요약 규칙이 근거 부족으로 묶음을 표시 대상에서 빼므로, 조회 창을 확인하려던 시험이 다른 이유로 통과한다(spec FR-003).
     */
    private void linkTopic(NewsArticle article) {
        Long topicId = linkRepository.ensureTopic("semiconductor", "반도체", true, LINK_RULE);
        linkRepository.replaceLinks(
                article.getId(), Set.of(), java.util.Map.of(topicId, "semiconductor"), LINK_RULE);
        flushAndClear();
    }

    private Integer countOf(String table) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }
}
