package com.ssafy.thorp.news.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ssafy.thorp.news.application.command.NewsDigestQuery;
import com.ssafy.thorp.news.application.exception.NewsQueryException;
import com.ssafy.thorp.news.application.repository.NewsArticleLinkRepository;
import com.ssafy.thorp.news.application.repository.NewsCollectionStateRepository;
import com.ssafy.thorp.news.application.repository.NewsDigestRepository;
import com.ssafy.thorp.news.application.repository.NewsPublisherRepository;
import com.ssafy.thorp.news.application.result.NewsDigestListResult;
import com.ssafy.thorp.news.domain.model.NewsArticle;
import com.ssafy.thorp.news.domain.model.NewsCollectionState;
import com.ssafy.thorp.news.domain.model.NewsDigest;
import com.ssafy.thorp.news.domain.model.NewsPublisher;
import com.ssafy.thorp.news.domain.vo.CanonicalNewsUrl;
import com.ssafy.thorp.news.domain.vo.NewsDigestId;
import com.ssafy.thorp.news.domain.vo.NewsExposureMode;
import com.ssafy.thorp.news.domain.vo.NewsSourceType;
import com.ssafy.thorp.news.domain.vo.PermissionStatus;
import com.ssafy.thorp.news.domain.vo.PublicationTimeStatus;
import com.ssafy.thorp.news.infrastructure.config.NewsLinkRegistry;
import com.ssafy.thorp.news.infrastructure.config.RegistryNewsTopicCatalog;
import com.ssafy.thorp.news.support.RecordingNewsMetrics;
import com.ssafy.thorp.news.support.StubSectorCatalog;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 노출 모드에 따라 요약을 어디서 가져오는지(spec FR-003·FR-016, plan "요약·정렬 규칙 v1", T079).
 *
 * <p>모드마다 답이 다르다. 공개 모드는 허가된 기사만으로 <b>다시 만들고</b>, 내부 모드는 <b>저장된 값을 그대로</b> 쓴다. 저장된 값이 곧 검증 대상이라 조회가
 * 다시 만들어 버리면 수집이 무엇을 저장했는지 영영 확인할 수 없다.
 *
 * <p>그 차이를 드러내는 입력은 <b>주제가 연결되지 않은 묶음</b>이다. 요약 규칙은 주제가 없으면 문장을 만들지 못하므로, 다시 만드는 쪽에서는 그 묶음이 사라지고 저장
 * 값을 쓰는 쪽에서는 남는다.
 */
class NewsDigestQueryServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-10T06:00:00Z");
    private static final String STORED_SUMMARY = "저장된 요약 문장입니다.";

    private static final NewsPublisher PUBLISHER =
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

    /** 주제가 연결되지 않은 기사 하나로 만든 묶음. */
    private static NewsDigest digestWithoutTopics() {
        CanonicalNewsUrl canonical = CanonicalNewsUrl.normalize("https://example.co.kr/1");
        NewsArticle article =
                NewsArticle.restore(
                        10L,
                        com.ssafy.thorp.news.domain.vo.NewsArticleId.of(canonical),
                        NewsSourceType.RSS,
                        PUBLISHER.getId(),
                        null,
                        canonical.value(),
                        canonical,
                        "반도체 수출 지표가 발표됐다",
                        "ko",
                        NOW.minus(Duration.ofHours(1)),
                        PublicationTimeStatus.VERIFIED,
                        NOW.minus(Duration.ofHours(1)),
                        null,
                        canonical.value(),
                        "hash",
                        "run-1",
                        true);
        return NewsDigest.restore(
                100L,
                NewsDigestId.of(article.getArticleId(), NewsGroupingPolicy.RULE_VERSION),
                List.of(article),
                article,
                article.getTitle(),
                STORED_SUMMARY,
                NewsGroupingPolicy.RULE_VERSION,
                NewsImportancePolicy.RULE_VERSION);
    }

    /** 회신하지 않은 매체. 공개 환경에 나오면 안 된다(spec FR-016). */
    private static final NewsPublisher PENDING_PUBLISHER =
            NewsPublisher.restore(
                    1L,
                    "pending-press",
                    "회신 전 매체",
                    "ko",
                    "pending.co.kr",
                    "https://rss.pending.co.kr/economy.xml",
                    PermissionStatus.PENDING,
                    NOW,
                    true,
                    null,
                    null);

    private static NewsDigestQueryService service(NewsExposureMode mode, NewsPublisher publisher) {
        RegistryNewsTopicCatalog topics =
                new RegistryNewsTopicCatalog(
                        new NewsLinkRegistry("news-link-v1", List.of(), List.of()));
        return new NewsDigestQueryService(
                new SingleDigestStore(digestWithoutTopics()),
                new SinglePublisherStore(publisher),
                new EmptyStateStore(),
                new EmptyLinkStore(),
                new NewsSummaryPolicy(topics),
                new NewsImportancePolicy(),
                new NewsFreshnessPolicy(),
                new NewsSectorQueryPolicy(new StubSectorCatalog()),
                new NewsDigestQueryService.NewsQuerySettings(
                        mode, Duration.ofMinutes(30), 3, Duration.ofHours(48), 93),
                new RecordingNewsMetrics(),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    @DisplayName("공개 모드는 회신하지 않은 매체를 거른다")
    void 공개_모드는_미허가를_거른다() {
        NewsDigestListResult result =
                service(NewsExposureMode.PUBLIC, PENDING_PUBLISHER)
                        .query(List.of(), null, null, null);

        assertThat(result.digests()).as("회신이 없는 매체의 항목은 공개 환경에 표시하지 않는다(spec FR-016)").isEmpty();
        assertThat(result.state())
                .as("공개할 수 있는 매체가 없으니 '준비 중'이다")
                .isEqualTo(com.ssafy.thorp.news.domain.vo.NewsDigestState.PREPARING);
        assertThat(result.lastUpdatedAt()).as("허가 매체가 없으면 공개 화면이 말할 수 있는 갱신 시각도 없다").isNull();
        assertThat(result.stale()).as("한 번도 공개한 적이 없는 것은 지연이 아니다").isFalse();
        assertThat(result.statusMessage()).isEqualTo(NewsFreshnessPolicy.PREPARING_MESSAGE);
    }

    @Test
    @DisplayName("내부 모드는 회신 전 매체도 보여 준다")
    void 내부_모드는_회신_전도_본다() {
        NewsDigestListResult result =
                service(NewsExposureMode.INTERNAL, PENDING_PUBLISHER)
                        .query(List.of(), null, null, null);

        assertThat(result.digests())
                .as("회신을 기다리는 동안 수집이 제대로 도는지 내부에서 확인할 길이 있어야 한다(spec FR-016)")
                .hasSize(1);
    }

    @Test
    @DisplayName("공개 모드는 허가된 매체라도 요약 근거가 없으면 뺀다")
    void 공개_모드는_요약을_재계산한다() {
        NewsDigestListResult result =
                service(NewsExposureMode.PUBLIC, PUBLISHER).query(List.of(), null, null, null);

        assertThat(result.digests())
                .as("주제 근거가 없으면 요약을 만들 수 없어 그 묶음은 표시하지 않는다(spec FR-003)")
                .isEmpty();
    }

    private static NewsDigestListResult query(NewsExposureMode mode) {
        RegistryNewsTopicCatalog topics =
                new RegistryNewsTopicCatalog(
                        new NewsLinkRegistry("news-link-v1", List.of(), List.of()));
        NewsDigestQueryService service =
                new NewsDigestQueryService(
                        new SingleDigestStore(digestWithoutTopics()),
                        new SinglePublisherStore(PUBLISHER),
                        new EmptyStateStore(),
                        new EmptyLinkStore(),
                        new NewsSummaryPolicy(topics),
                        new NewsImportancePolicy(),
                        new NewsFreshnessPolicy(),
                        new NewsSectorQueryPolicy(new StubSectorCatalog()),
                        new NewsDigestQueryService.NewsQuerySettings(
                                mode, Duration.ofMinutes(30), 3, Duration.ofHours(48), 93),
                        new RecordingNewsMetrics(),
                        Clock.fixed(NOW, ZoneOffset.UTC));
        return service.query(NewsDigestQuery.current(null));
    }

    @Test
    @DisplayName("내부 모드는 저장된 사실 요약을 그대로 쓴다")
    void 내부_모드는_저장값() {
        NewsDigestListResult result = query(NewsExposureMode.INTERNAL);

        assertThat(result.digests()).hasSize(1);
        assertThat(result.digests().get(0).factualSummary())
                .as("저장된 값이 곧 검증 대상이다. 조회가 다시 만들면 무엇을 저장했는지 볼 수 없다")
                .isEqualTo(STORED_SUMMARY);
    }

    @Test
    @DisplayName("공개 모드는 요약을 다시 만들고 근거가 없으면 그 묶음을 뺀다")
    void 공개_모드는_재계산() {
        NewsDigestListResult result = query(NewsExposureMode.PUBLIC);

        assertThat(result.digests())
                .as("저장된 요약에는 미허가 매체의 제목·매체명이 남아 있을 수 있다(spec FR-016)")
                .isEmpty();
    }

    /** 그 시각에 발행된 기사 하나로 만든 묶음. 매체가 하나라 주요도는 발행 시각 순서가 된다. */
    private static NewsDigest digestAt(long id, String path, Duration ago) {
        CanonicalNewsUrl canonical = CanonicalNewsUrl.normalize("https://example.co.kr/" + path);
        NewsArticle article =
                NewsArticle.restore(
                        id,
                        com.ssafy.thorp.news.domain.vo.NewsArticleId.of(canonical),
                        NewsSourceType.RSS,
                        PUBLISHER.getId(),
                        null,
                        canonical.value(),
                        canonical,
                        "기사 " + path,
                        "ko",
                        NOW.minus(ago),
                        PublicationTimeStatus.VERIFIED,
                        NOW.minus(ago),
                        null,
                        canonical.value(),
                        "hash-" + path,
                        "run-1",
                        true);
        return NewsDigest.restore(
                id * 10,
                NewsDigestId.of(article.getArticleId(), NewsGroupingPolicy.RULE_VERSION),
                List.of(article),
                article,
                article.getTitle(),
                STORED_SUMMARY,
                NewsGroupingPolicy.RULE_VERSION,
                NewsImportancePolicy.RULE_VERSION);
    }

    private static final NewsDigest NEWEST = digestAt(11L, "newest", Duration.ofHours(1));
    private static final NewsDigest MIDDLE = digestAt(12L, "middle", Duration.ofHours(2));
    private static final NewsDigest OLDEST = digestAt(13L, "oldest", Duration.ofHours(3));
    private static final NewsDigest EARLIEST = digestAt(14L, "earliest", Duration.ofHours(4));

    /** 섹터 A에는 NEWEST·OLDEST, 섹터 B에는 OLDEST·MIDDLE·EARLIEST가 연결된 저장소. OLDEST는 두 섹터에 걸친다. */
    private static NewsDigestQueryService sectorService(int maxDigests) {
        RegistryNewsTopicCatalog topics =
                new RegistryNewsTopicCatalog(
                        new NewsLinkRegistry("news-link-v1", List.of(), List.of()));
        return new NewsDigestQueryService(
                new SectorDigestStore(
                        Map.of(
                                "A", List.of(NEWEST, OLDEST),
                                "B", List.of(OLDEST, MIDDLE, EARLIEST))),
                new SinglePublisherStore(PUBLISHER),
                new EmptyStateStore(),
                new EmptyLinkStore(),
                new NewsSummaryPolicy(topics),
                new NewsImportancePolicy(),
                new NewsFreshnessPolicy(),
                new NewsSectorQueryPolicy(new StubSectorCatalog(Set.of("A", "B"))),
                new NewsDigestQueryService.NewsQuerySettings(
                        NewsExposureMode.INTERNAL,
                        Duration.ofMinutes(30),
                        maxDigests,
                        Duration.ofHours(48),
                        93),
                new RecordingNewsMetrics(),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static List<String> titlesOf(NewsDigestListResult result) {
        return result.digests().stream()
                .map(NewsDigestListResult.Digest::representativeTitle)
                .toList();
    }

    @Test
    @DisplayName("섹터 여럿이면 그중 하나 이상에 걸린 묶음을 한 번씩만 주요도 순으로 준다 (spec FR-001 ③)")
    void 섹터_여럿을_합친다() {
        NewsDigestListResult result = sectorService(10).query(List.of("A", "B"), null, null, 4);

        assertThat(titlesOf(result))
                .as("두 섹터에 걸친 묶음도 한 번만 나오고, 순서는 화면이 아니라 주요도 규칙이 정한다(spec FR-004)")
                .containsExactly("기사 newest", "기사 middle", "기사 oldest", "기사 earliest");
        assertThat(result.sectorId()).as("섹터가 여럿이면 응답의 sectorId는 비운다").isNull();
    }

    @Test
    @DisplayName("limit만큼만 주고, 서버 상한보다 크면 상한까지만 준다. 주지 않으면 예전처럼 셋이다")
    void limit과_상한() {
        assertThat(titlesOf(sectorService(10).query(List.of("A", "B"), null, null, 1)))
                .containsExactly("기사 newest");
        assertThat(titlesOf(sectorService(10).query(List.of("A", "B"), null, null, null)))
                .as("limit 없이 부르는 사례 화면은 받은 묶음을 모두 그린다. 상한을 올려도 그 화면은 셋이어야 한다")
                .containsExactly("기사 newest", "기사 middle", "기사 oldest");
        assertThat(titlesOf(sectorService(2).query(List.of("A", "B"), null, null, 10)))
                .as("상한을 넘는 요청은 상한까지만이다. 모자라면 채우지 않는 것과 같은 규칙이다(spec FR-001)")
                .containsExactly("기사 newest", "기사 middle");
    }

    @Test
    @DisplayName("묶음이 셋이 안 되면 있는 만큼만 주고 다른 섹터 묶음으로 채우지 않는다 (spec FR-001)")
    void 부족하면_채우지_않는다() {
        assertThat(titlesOf(sectorService(10).query(List.of("A"), null, null, null)))
                .as("개수를 맞추려고 관련 없는 기사를 끌어오면 화면은 그것을 '주요 뉴스'로 읽는다(원칙 VI)")
                .containsExactly("기사 newest", "기사 oldest");
    }

    @Test
    @DisplayName("섹터 가운데 하나라도 등록되지 않은 값이면 요청을 거절한다")
    void 모르는_섹터가_섞이면_거절() {
        assertThatThrownBy(() -> sectorService(5).query(List.of("A", "UNKNOWN"), null, null, null))
                .isInstanceOf(NewsQueryException.class);
    }

    /** 섹터마다 정해 둔 묶음을 돌려주는 저장소 대역. 여러 섹터 조회는 저장소 기본 구현이 합친다. */
    private record SectorDigestStore(Map<String, List<NewsDigest>> bySector)
            implements NewsDigestRepository {

        @Override
        public NewsDigest save(NewsDigest digest) {
            return digest;
        }

        @Override
        public Optional<NewsDigest> findByDigestId(NewsDigestId digestId) {
            return Optional.empty();
        }

        @Override
        public List<NewsDigest> findRecent(Instant since) {
            return bySector.values().stream().flatMap(List::stream).distinct().toList();
        }

        @Override
        public List<NewsDigest> findRecentBySector(Instant since, String sectorId) {
            return bySector.getOrDefault(sectorId, List.of());
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
            // 조회 시험이라 쓰지 않는다.
        }
    }

    /** 묶음 하나만 돌려주는 저장소 대역. */
    private record SingleDigestStore(NewsDigest digest) implements NewsDigestRepository {

        @Override
        public NewsDigest save(NewsDigest digest) {
            return digest;
        }

        @Override
        public Optional<NewsDigest> findByDigestId(NewsDigestId digestId) {
            return Optional.empty();
        }

        @Override
        public List<NewsDigest> findRecent(Instant since) {
            return List.of(digest);
        }

        @Override
        public List<NewsDigest> findRecentBySector(Instant since, String sectorId) {
            return List.of(digest);
        }

        @Override
        public List<NewsDigest> findVerifiedBetween(Instant from, Instant to, String sectorId) {
            return List.of(digest);
        }

        @Override
        public List<NewsDigest> findByArticleIds(List<Long> articleIds) {
            return List.of();
        }

        @Override
        public void deleteByArticleIds(List<Long> articleIds) {
            // 조회 시험이라 쓰지 않는다.
        }
    }

    /** 매체 하나만 아는 저장소 대역. 허가 상태에 따라 공개 목록에서 빠진다. */
    private record SinglePublisherStore(NewsPublisher publisher)
            implements NewsPublisherRepository {

        @Override
        public NewsPublisher save(NewsPublisher publisher) {
            return publisher;
        }

        @Override
        public Optional<NewsPublisher> findById(Long id) {
            return Optional.of(publisher);
        }

        @Override
        public Optional<NewsPublisher> findByPublisherKey(String publisherKey) {
            return Optional.of(publisher);
        }

        @Override
        public Optional<NewsPublisher> findByRegisteredDomain(String registeredDomain) {
            return Optional.of(publisher);
        }

        @Override
        public List<NewsPublisher> findEnabled() {
            return List.of(publisher);
        }

        @Override
        public List<NewsPublisher> findPubliclyVisible() {
            return publisher.isPubliclyVisible() ? List.of(publisher) : List.of();
        }

        @Override
        public boolean existsPubliclyVisible() {
            return publisher.isPubliclyVisible();
        }
    }

    private static final class EmptyStateStore implements NewsCollectionStateRepository {

        @Override
        public NewsCollectionState save(NewsCollectionState state) {
            return state;
        }

        @Override
        public Optional<NewsCollectionState> findBySourceKey(String sourceKey) {
            return Optional.empty();
        }

        @Override
        public List<NewsCollectionState> findDue(Instant now) {
            return List.of();
        }

        @Override
        public List<NewsCollectionState> findAll() {
            return List.of();
        }

        /**
         * 마지막 갱신 시각.
         *
         * <p>{@code publicOnly}면 빈 값이다. 실제 질의가 공개 노출 가능한 매체의 뉴스원만 세는데, 이 대역이 그것을 무시하면 허가 매체가 없는
         * 상황에서도 시각이 실려 '준비 중'인데 갱신 시각이 있는 응답이 만들어진다.
         */
        @Override
        public Optional<Instant> findLatestSuccessAt(boolean publicOnly) {
            return publicOnly ? Optional.empty() : Optional.of(NOW.minus(Duration.ofMinutes(5)));
        }
    }

    /** 연결이 하나도 없는 저장소. 주제가 없는 상황을 만든다. */
    private static final class EmptyLinkStore implements NewsArticleLinkRepository {

        @Override
        public Long ensureTopic(
                String topicKey, String displayName, boolean domesticLink, String ruleVersion) {
            return 1L;
        }

        @Override
        public void replaceLinks(
                Long articleId,
                Set<String> sectorIds,
                Map<Long, String> topicEvidenceByTopicId,
                String ruleVersion) {
            // 조회 시험이라 쓰지 않는다.
        }

        @Override
        public void addLinks(
                Long articleId,
                Set<String> sectorIds,
                Map<Long, String> topicEvidenceByTopicId,
                String ruleVersion) {
            // 조회 시험이라 쓰지 않는다.
        }

        @Override
        public Map<Long, Set<String>> findTopicKeysByArticleIds(Collection<Long> articleIds) {
            return Map.of();
        }

        @Override
        public Map<Long, Set<String>> findSectorIdsByArticleIds(Collection<Long> articleIds) {
            return Map.of();
        }
    }
}
