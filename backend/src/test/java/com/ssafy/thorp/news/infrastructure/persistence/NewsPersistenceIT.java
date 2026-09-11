package com.ssafy.thorp.news.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ssafy.thorp.common.config.JpaAuditingConfig;
import com.ssafy.thorp.news.application.repository.NewsArticleRepository;
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
    DatabaseNewsCollectionLease.class
})
class NewsPersistenceIT extends PostgresTestSupport {

    private static final Instant NOW = Instant.parse("2026-09-10T06:00:00Z");
    private static final String GROUPING_RULE = "news-grouping-v1";
    private static final String IMPORTANCE_RULE = "news-importance-v1";

    @TestConfiguration
    static class NewsTestConfig {
        @Bean
        NewsProperties newsProperties() {
            return new NewsProperties(
                    NewsProperties.ExposureMode.PUBLIC,
                    Duration.ofMinutes(30),
                    Duration.ofMinutes(15),
                    Duration.ofMinutes(15),
                    LocalDate.of(2026, 11, 7),
                    "Asia/Seoul",
                    93,
                    20,
                    3,
                    new NewsProperties.Http(
                            3,
                            Duration.ofSeconds(5),
                            Duration.ofSeconds(20),
                            10_485_760L,
                            Set.of("application/xml"),
                            Set.of("application/json"),
                            List.of("api.gdeltproject.org")));
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
    @DisplayName("V3가 seed한 규칙 버전 네 개를 읽을 수 있다")
    void seededRuleVersionsAreReadable() {
        assertThat(ruleVersionRepository.findAll()).hasSize(4);

        assertThat(ruleVersionRepository.findEffective(NewsRuleVersion.RuleType.GROUPING, NOW))
                .get()
                .satisfies(
                        rule -> {
                            assertThat(rule.ruleVersion()).isEqualTo(GROUPING_RULE);
                            assertThat(rule.definition()).containsKey("titleJaccardThreshold");
                        });
    }

    private Integer countOf(String table) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }
}
