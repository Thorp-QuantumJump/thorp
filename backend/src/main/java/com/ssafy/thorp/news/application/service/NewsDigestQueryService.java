package com.ssafy.thorp.news.application.service;

import com.ssafy.thorp.news.application.command.NewsDigestQuery;
import com.ssafy.thorp.news.application.port.NewsMetrics;
import com.ssafy.thorp.news.application.repository.NewsArticleLinkRepository;
import com.ssafy.thorp.news.application.repository.NewsCollectionStateRepository;
import com.ssafy.thorp.news.application.repository.NewsDigestRepository;
import com.ssafy.thorp.news.application.repository.NewsPublisherRepository;
import com.ssafy.thorp.news.application.result.NewsDigestListResult;
import com.ssafy.thorp.news.domain.model.NewsArticle;
import com.ssafy.thorp.news.domain.model.NewsDigest;
import com.ssafy.thorp.news.domain.model.NewsPublisher;
import com.ssafy.thorp.news.domain.vo.NewsDigestState;
import com.ssafy.thorp.news.domain.vo.NewsExposureMode;
import com.ssafy.thorp.news.domain.vo.PublicationTimeStatus;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 저장된 뉴스 묶음을 화면이 쓸 모양으로 돌려준다(spec FR-001·FR-005·FR-006·FR-014·FR-016).
 *
 * <p>순서가 규칙이다. <b>허가 필터 → 묶음 재계산 → 주요도 정렬 → 상위 N개</b>. 이 차례를 바꾸면 미허가 매체가 매체 수와 최근성에 섞여 순서에 간접적으로 새어
 * 든다. 미허가 매체의 존재가 대표 제목·요약·매체 수·순서 어디에도 반영되면 안 된다(spec FR-016).
 *
 * <p>저장된 묶음 행은 전체 멤버 기준 값을 유지한다. 허가 상태가 바뀌었다고 저장 값을 덮어쓰지 않는다 — 허가가 복원되면 원래 구성이 그대로 살아나야 하고, 저장 값을
 * 고쳐 버리면 그 복원이 불가능해진다(원칙 VIII).
 *
 * <p><b>빈 결과를 만들어 내지 않는다.</b> 세 묶음이 안 되면 있는 만큼만 돌려준다. 자료가 없거나 수집이 늦었거나 허가를 기다리는 것은 전부 오류가 아니라 상태이며
 * HTTP 200이다(원칙 VI, spec FR-024).
 */
@Service
public class NewsDigestQueryService {

    private final NewsDigestRepository digests;
    private final NewsPublisherRepository publishers;
    private final NewsCollectionStateRepository collectionStates;
    private final NewsArticleLinkRepository links;
    private final NewsSummaryPolicy summaries;
    private final NewsImportancePolicy importance;
    private final NewsFreshnessPolicy freshness;
    private final NewsSectorQueryPolicy sectorPolicy;
    private final NewsQuerySettings settings;
    private final NewsMetrics metrics;
    private final Clock clock;

    public NewsDigestQueryService(
            NewsDigestRepository digests,
            NewsPublisherRepository publishers,
            NewsCollectionStateRepository collectionStates,
            NewsArticleLinkRepository links,
            NewsSummaryPolicy summaries,
            NewsImportancePolicy importance,
            NewsFreshnessPolicy freshness,
            NewsSectorQueryPolicy sectorPolicy,
            NewsQuerySettings settings,
            NewsMetrics metrics,
            Clock clock) {
        this.digests = digests;
        this.publishers = publishers;
        this.collectionStates = collectionStates;
        this.links = links;
        this.summaries = summaries;
        this.importance = importance;
        this.freshness = freshness;
        this.sectorPolicy = sectorPolicy;
        this.settings = settings;
        this.metrics = metrics;
        this.clock = clock;
    }

    /**
     * 조회 설정. 서버가 정하며 클라이언트가 고를 수 없다(spec FR-008·FR-016).
     *
     * @param exposureMode 노출 모드. PUBLIC이면 허가된 매체만 쓴다.
     * @param staleThreshold 마지막 갱신이 이보다 오래되면 지연으로 본다.
     * @param maxDigests 한 응답의 묶음 수 상한.
     * @param currentWindow 현재 뉴스로 볼 최근 구간.
     * @param maxHistoricalRangeDays 과거 조회 요청 구간 상한(일).
     */
    public record NewsQuerySettings(
            NewsExposureMode exposureMode,
            Duration staleThreshold,
            int maxDigests,
            Duration currentWindow,
            int maxHistoricalRangeDays) {}

    /**
     * 요청 파라미터로 조회를 만들어 실행한다. 엔드포인트가 부르는 자리다.
     *
     * <p><b>노출 모드는 배포 단위가 정한다.</b> 내부 검증 환경은 {@code INTERNAL}로 떠서 회신 전 매체의 기사까지 보고, 공개 환경은 {@code
     * PUBLIC}으로 떠서 허가된 매체만 내보낸다. 클라이언트는 이 값을 고를 수 없다(plan "조회와 상태").
     *
     * <p>설정 하나가 곧 공개 범위라 위험하다. 그래서 두 겹으로 막는다 — 기본값이 {@code PUBLIC}이고, 운영 프로필에서 {@code INTERNAL}이면
     * {@code NewsExposureModeGuard}가 기동을 막는다. 여기서 모드를 강제하지 않는 이유는 그러면 내부 검증 환경이 존재할 수 없기 때문이다 (spec
     * FR-016).
     *
     * <p>구간 상한이 설정값이라 조회 객체를 만드는 일도 여기 있다. 컨트롤러가 만들면 설정을 읽어야 하고, 설정 record는 infrastructure에 있어
     * presentation이 infrastructure를 참조하게 된다(ADR-0001 계층 방향).
     */
    @Transactional(readOnly = true)
    public NewsDigestListResult query(String sectorId, Instant from, Instant to) {
        return query(
                NewsDigestQuery.of(sectorId, from, to, settings.maxHistoricalRangeDays()),
                settings.exposureMode());
    }

    /** 설정이 정한 모드로 조회한다. */
    @Transactional(readOnly = true)
    public NewsDigestListResult query(NewsDigestQuery query) {
        return query(query, settings.exposureMode());
    }

    /** 조회를 실행한다. 읽기만 하므로 쓰기 트랜잭션을 열지 않는다. */
    @Transactional(readOnly = true)
    public NewsDigestListResult query(NewsDigestQuery query, NewsExposureMode mode) {
        long startedAt = System.nanoTime();
        NewsDigestListResult result = doQuery(query, mode);
        metrics.recordQuery(result.state().name(), Duration.ofNanos(System.nanoTime() - startedAt));
        return result;
    }

    private NewsDigestListResult doQuery(NewsDigestQuery query, NewsExposureMode mode) {
        Instant now = clock.instant();
        Instant lastUpdatedAt =
                collectionStates.findLatestSuccessAt(mode.requiresPermissionFilter()).orElse(null);

        // 요청이 유효한지를 먼저 본다. 등록되지 않은 섹터는 매체 허가 상태와 무관하게 잘못된 요청이다.
        // 이 검사를 상태 판정 뒤로 미루면, 매체 회신 전에는 어떤 오타를 보내도 '준비 중'이 돌아와
        // 화면을 만드는 쪽이 잘못된 요청을 눈치채지 못한다(spec FR-005).
        boolean sectorResolvable = sectorPolicy.canQuery(query.sectorId());

        List<NewsPublisher> visible = visiblePublishers(mode);
        if (visible.isEmpty()) {
            // 공개할 수 있는 매체가 없다. 자료 유무를 보기 전에 '준비 중'이다(spec FR-016).
            return empty(query, NewsDigestState.PREPARING, lastUpdatedAt, now);
        }

        if (!sectorResolvable) {
            // 001의 대응표가 아직 발행되지 않았다. 확인할 섹터가 없으므로 빈 화면이다(spec FR-005).
            return empty(query, NewsDigestState.EMPTY, lastUpdatedAt, now);
        }

        Map<Long, NewsPublisher> byId = new java.util.HashMap<>();
        visible.forEach(publisher -> byId.put(publisher.getId(), publisher));
        Predicate<NewsArticle> permitted = article -> byId.containsKey(article.getPublisherId());

        List<NewsDigest.Projection> projections =
                candidates(query, now).stream()
                        .map(digest -> project(digest, permitted, query, mode))
                        .flatMap(Optional::stream)
                        .toList();

        List<NewsDigest.Projection> top = importance.top(projections, settings.maxDigests());
        NewsDigestState state =
                freshness.stateOf(
                        true, !top.isEmpty(), lastUpdatedAt, now, settings.staleThreshold());

        return new NewsDigestListResult(
                query.context(),
                state,
                query.sectorId(),
                query.from(),
                query.to(),
                lastUpdatedAt,
                freshness.isStale(lastUpdatedAt, now, settings.staleThreshold()),
                freshness.messageOf(state, lastUpdatedAt, query.isHistorical()),
                toDigests(top, byId));
    }

    /**
     * 조회 대상 묶음을 읽는다.
     *
     * <p>과거 조회는 검증된 발행 시각이 구간 안인 기사만 본다. GDELT 기록 시각을 발행 시각으로 쓰지 않는다 — 그 값은 우리가 받은 시각이지 매체가 낸 시각이
     * 아니다(spec FR-006·FR-012, 원칙 V).
     */
    private List<NewsDigest> candidates(NewsDigestQuery query, Instant now) {
        if (query.isHistorical()) {
            return digests.findVerifiedBetween(query.from(), query.to(), query.sectorId());
        }
        Instant since = now.minus(settings.currentWindow());
        return query.hasSector()
                ? digests.findRecentBySector(since, query.sectorId())
                : digests.findRecent(since);
    }

    /**
     * 허가된 기사만으로 묶음을 다시 계산한다.
     *
     * <p>과거 조회에서는 발행 시각이 확인된 기사만 남긴다. 미확인 기사가 섞이면 그 기사가 정말 그 구간에 나왔는지 말할 수 없으면서 매체 수를 늘린다(spec
     * FR-006).
     */
    private Optional<NewsDigest.Projection> project(
            NewsDigest digest,
            Predicate<NewsArticle> permitted,
            NewsDigestQuery query,
            NewsExposureMode mode) {
        Predicate<NewsArticle> filter = permitted;
        if (query.isHistorical()) {
            filter = filter.and(article -> withinRequestedRange(article, query));
        }
        return digest.projectFor(filter, summaryRuleFor(digest, mode));
    }

    private static boolean withinRequestedRange(NewsArticle article, NewsDigestQuery query) {
        if (!article.getPublishedAtStatus().isUsableForHistory()) {
            return false;
        }
        Instant publishedAt = article.getPublishedAt();
        return publishedAt != null
                && !publishedAt.isBefore(query.from())
                && !publishedAt.isAfter(query.to());
    }

    /**
     * 이 묶음의 요약을 어떻게 만들지 고른다(spec FR-003, plan "요약·정렬 규칙 v1").
     *
     * <p><b>공개 모드에서는 다시 만든다.</b> 저장된 값은 전체 멤버 기준이라 미허가 매체의 제목·매체명이 문장에 남아 있을 수 있다. 허가되지 않은 매체의 존재가
     * 요약에 간접적으로도 비치면 안 된다(spec FR-016).
     *
     * <p><b>내부 모드에서는 저장된 값을 쓴다.</b> 그 값이 바로 검증 대상이다 — 수집이 무엇을 만들어 저장했는지 보려는 것인데 조회가 다시 만들어 버리면 저장된
     * 문장을 영영 확인할 수 없다.
     */
    private NewsDigest.SummaryRule summaryRuleFor(NewsDigest digest, NewsExposureMode mode) {
        if (!mode.requiresPermissionFilter()) {
            return (representative, members) -> Optional.of(digest.getFactualSummary());
        }
        return this::summarize;
    }

    /**
     * 남은 기사만으로 사실 요약을 다시 만든다(spec FR-003).
     *
     * <p>요약 근거가 부족하면 빈 값을 돌려주고, 그러면 그 묶음은 표시되지 않는다.
     */
    private Optional<String> summarize(NewsArticle representative, List<NewsArticle> members) {
        String publisherName =
                publishers
                        .findById(representative.getPublisherId())
                        .map(NewsPublisher::getDisplayName)
                        .orElse(null);
        Set<String> topicKeys =
                links.findTopicKeysByArticleIds(List.of(representative.getId()))
                        .getOrDefault(representative.getId(), Set.of());
        int distinctPublishers =
                (int) members.stream().map(NewsArticle::getPublisherId).distinct().count();
        return summaries.summarize(
                new NewsSummaryPolicy.Input(
                        representative.getTitle(), publisherName, topicKeys, distinctPublishers));
    }

    private List<NewsPublisher> visiblePublishers(NewsExposureMode mode) {
        return mode.requiresPermissionFilter()
                ? publishers.findPubliclyVisible()
                : publishers.findEnabled();
    }

    private List<NewsDigestListResult.Digest> toDigests(
            List<NewsDigest.Projection> projections, Map<Long, NewsPublisher> publishersById) {
        List<Long> articleIds =
                projections.stream()
                        .flatMap(projection -> projection.articles().stream())
                        .map(NewsArticle::getId)
                        .toList();
        Map<Long, Set<String>> topicsByArticle = links.findTopicKeysByArticleIds(articleIds);
        Map<Long, Set<String>> sectorsByArticle = links.findSectorIdsByArticleIds(articleIds);

        List<NewsDigestListResult.Digest> result = new ArrayList<>();
        for (NewsDigest.Projection projection : projections) {
            result.add(
                    new NewsDigestListResult.Digest(
                            projection.digestId().value(),
                            projection.representativeTitle(),
                            projection.factualSummary(),
                            projection.firstPublishedAt(),
                            projection.hasVerifiedPublishedAt()
                                    ? PublicationTimeStatus.VERIFIED
                                    : PublicationTimeStatus.UNKNOWN,
                            projection.distinctPublisherCount(),
                            collect(projection, sectorsByArticle),
                            collect(projection, topicsByArticle),
                            projection.importanceRuleVersion(),
                            toArticles(projection, publishersById)));
        }
        return result;
    }

    /** 묶음에 속한 기사들의 연결을 합친다. 순서를 보존해 같은 입력이 같은 응답을 내게 한다. */
    private static List<String> collect(
            NewsDigest.Projection projection, Map<Long, Set<String>> byArticle) {
        Set<String> merged = new LinkedHashSet<>();
        projection
                .articles()
                .forEach(
                        article ->
                                merged.addAll(byArticle.getOrDefault(article.getId(), Set.of())));
        return List.copyOf(merged);
    }

    private static List<NewsDigestListResult.Article> toArticles(
            NewsDigest.Projection projection, Map<Long, NewsPublisher> publishersById) {
        return projection.articles().stream()
                .map(
                        article ->
                                new NewsDigestListResult.Article(
                                        article.getArticleId().value(),
                                        article.getTitle(),
                                        Optional.ofNullable(
                                                        publishersById.get(
                                                                article.getPublisherId()))
                                                .map(NewsPublisher::getDisplayName)
                                                .orElse(null),
                                        article.getPublishedAt(),
                                        article.getPublishedAtStatus(),
                                        article.getOriginalUrl()))
                .toList();
    }

    private NewsDigestListResult empty(
            NewsDigestQuery query, NewsDigestState state, Instant lastUpdatedAt, Instant now) {
        return new NewsDigestListResult(
                query.context(),
                state,
                query.sectorId(),
                query.from(),
                query.to(),
                lastUpdatedAt,
                freshness.isStale(lastUpdatedAt, now, settings.staleThreshold()),
                freshness.messageOf(state, lastUpdatedAt, query.isHistorical()),
                List.of());
    }
}
