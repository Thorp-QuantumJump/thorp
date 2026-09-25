package com.ssafy.thorp.news.application.service;

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
import com.ssafy.thorp.news.domain.vo.NewsSourceType;
import com.ssafy.thorp.news.domain.vo.PublicationTimeStatus;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 한 뉴스원의 수집 회차를 조합한다(spec FR-010·FR-013·FR-022).
 *
 * <p>순서가 규칙이다. 기간 → 관련성 → 중복 제거 → 저장 → 묶음이다. 관련성을 저장 뒤에 보면 버릴 기사를 먼저 쌓게 된다.
 *
 * <p><b>네트워크 호출은 트랜잭션 밖에서 한다.</b> 외부 응답을 기다리는 동안 DB 커넥션을 잡고 있으면 뉴스원 하나가 느려질 때 전체가 막힌다.
 *
 * <p><b>수집 위치는 저장이 확정된 뒤에만 전진한다.</b> 기사 저장·연결·묶음 재생성·상태 갱신이 한 트랜잭션이라 중단되면 함께 롤백되고, 다음 회차가 같은 구간을 다시
 * 처리해도 같은 결과가 된다. canonical URL의 UNIQUE 제약이 재처리를 멱등하게 만든다.
 */
@Service
public class NewsIngestionService {

    private static final Logger log = LoggerFactory.getLogger(NewsIngestionService.class);

    /** 기본 수집 주기. 채널이 더 긴 간격을 요청하면 그쪽을 따른다. */
    private static final Duration COLLECTION_INTERVAL = Duration.ofMinutes(15);

    private final NewsSourceClient sourceClient;
    private final NewsArticleRepository articles;
    private final NewsArticleLinkRepository links;
    private final NewsDigestRepository digests;
    private final NewsPublisherRepository publishers;
    private final NewsCollectionStateRepository collectionStates;
    private final NewsUrlNormalizer urlNormalizer;
    private final NewsRelevanceClassifier relevance;
    private final NewsGroupingPolicy grouping;
    private final NewsSummaryPolicy summaries;
    private final NewsTopicCatalog topicCatalog;
    private final NewsSectorLinkPolicy sectorLinks;
    private final NewsRetryPolicy retries;
    private final com.ssafy.thorp.news.application.port.NewsMetrics metrics;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;

    public NewsIngestionService(
            NewsSourceClient sourceClient,
            NewsArticleRepository articles,
            NewsArticleLinkRepository links,
            NewsDigestRepository digests,
            NewsPublisherRepository publishers,
            NewsCollectionStateRepository collectionStates,
            NewsUrlNormalizer urlNormalizer,
            NewsRelevanceClassifier relevance,
            NewsGroupingPolicy grouping,
            NewsSummaryPolicy summaries,
            NewsTopicCatalog topicCatalog,
            NewsSectorLinkPolicy sectorLinks,
            NewsRetryPolicy retries,
            com.ssafy.thorp.news.application.port.NewsMetrics metrics,
            TransactionTemplate transactionTemplate,
            Clock clock) {
        this.sourceClient = sourceClient;
        this.articles = articles;
        this.links = links;
        this.digests = digests;
        this.publishers = publishers;
        this.collectionStates = collectionStates;
        this.urlNormalizer = urlNormalizer;
        this.relevance = relevance;
        this.grouping = grouping;
        this.summaries = summaries;
        this.topicCatalog = topicCatalog;
        this.sectorLinks = sectorLinks;
        this.retries = retries;
        this.metrics = metrics;
        this.transactionTemplate = transactionTemplate;
        this.clock = clock;
    }

    /** 한 회차의 결과 요약. 지표와 로그에 쓴다. */
    public record IngestOutcome(
            String sourceKey,
            NewsCollectionState state,
            int collected,
            int excluded,
            int duplicates,
            int digestsTouched) {}

    /**
     * RSS 채널 한 곳을 수집한다.
     *
     * <p>{@code 304}는 성공으로 기록하되 수집 위치와 기사 데이터를 건드리지 않는다(spec FR-013).
     */
    public IngestOutcome ingest(RssNewsSource source, NewsPublisher publisher) {
        String sourceKey = source.sourceKey();
        NewsFetchResult fetched;
        try {
            // 네트워크는 트랜잭션 밖에서 부른다.
            fetched = withRetry(sourceKey, () -> sourceClient.fetch(source));
        } catch (NewsSourceClient.NewsSourceException e) {
            return failed(sourceKey, e);
        }

        // 응답이 준 조건부 요청 값을 매체에 돌려 저장한다. 이걸 저장하지 않으면 다음 회차가 빈 값으로 요청해
        // 뉴스원이 304를 줄 수 없고, 매번 전체 본문을 받게 된다(spec FR-013).
        rememberConditionalRequestState(publisher, fetched);

        // 매체가 밝힌 호출 간격을 지킨다. 지키지 않으면 부하를 늘리고 차단당할 수 있다(plan 수집과 재시도).
        Instant nextAttempt = fetched.channelHints().nextAttemptFrom(now(), COLLECTION_INTERVAL);

        if (fetched.notModified()) {
            NewsCollectionState state = loadState(sourceKey).notModified(now(), nextAttempt);
            return new IngestOutcome(sourceKey, collectionStates.save(state), 0, 0, 0, 0);
        }

        return inTransaction(
                () ->
                        persist(
                                sourceKey,
                                publisher,
                                fetched,
                                NewsSourceType.RSS,
                                false,
                                null,
                                nextAttempt,
                                source.channelTopicKeys()));
    }

    /**
     * GDELT 구간 한 개를 수집한다.
     *
     * <p>결과 상한에 걸리면 구간을 절반씩 나누어 다시 부른다. 최소 15분 구간에서도 상한이면 수신분만 저장하고 {@code INCOMPLETE}로 기록한다. 수집
     * 위치를 전진시키지 않아 다음 회차가 같은 구간을 재시도한다(spec FR-013).
     *
     * <p><b>회차 매체를 받지 않는다.</b> 해외 기사의 매체는 기사 주소의 host로 정하고 처음 보는 host는 미회신 상태로 등록한다. 등록된 매체가 하나도
     * 없어도 회차가 돌아야 한다 — 매체 등록은 공개 게이트와 별개로 진행되므로, 국내 매체를 먼저 요구하면 그동안 해외 보완 수집이 영영 돌지 않는다(spec
     * FR-009).
     */
    public IngestOutcome ingest(GdeltNewsSource source) {
        String sourceKey = source.sourceKey();
        List<CollectedNewsItem> items = new ArrayList<>();
        boolean incomplete;
        try {
            incomplete = collectWindow(source, items);
        } catch (NewsSourceClient.NewsSourceException e) {
            return failed(sourceKey, e);
        }

        String cursor = incomplete ? null : GdeltCursor.of(source.windowEnd());
        boolean partial = incomplete;
        return inTransaction(
                () ->
                        persist(
                                sourceKey,
                                null,
                                NewsFetchResult.complete(items, null, null),
                                NewsSourceType.GDELT_DOC,
                                true,
                                partial ? null : cursor,
                                nextAttempt(),
                                // GDELT는 조회에 쓴 주제가 항목마다 실려 오므로 채널 보완이 필요 없다.
                                List.of()));
    }

    /**
     * 구간을 받는다. 상한에 걸리면 절반씩 쪼개 다시 받는다.
     *
     * @return 최소 구간에서도 상한이 유지되면 참.
     */
    private boolean collectWindow(GdeltNewsSource window, List<CollectedNewsItem> sink) {
        NewsFetchResult result = withRetry(window.sourceKey(), () -> sourceClient.fetch(window));
        sink.addAll(result.items());

        if (!result.resultCapReached()) {
            return false;
        }
        if (!window.isDivisible()) {
            // 더 쪼갤 수 없다. 받은 만큼만 두고 구간을 완전 수집으로 기록하지 않는다.
            log.warn(
                    "GDELT 구간이 결과 상한에 걸렸고 더 쪼갤 수 없다 source={} category={}",
                    window.sourceKey(),
                    NewsCollectionState.RESULT_CAP_REACHED);
            return true;
        }
        boolean firstIncomplete = collectWindow(window.firstHalf(), sink);
        boolean secondIncomplete = collectWindow(window.secondHalf(), sink);
        return firstIncomplete || secondIncomplete;
    }

    /**
     * 한 회차의 결과를 한 트랜잭션으로 반영한다.
     *
     * <p>기사 저장 → 섹터·주제 연결 → 묶음 재생성 → 수집 상태 갱신 순서다. 중단되면 함께 롤백된다.
     */
    private IngestOutcome persist(
            String sourceKey,
            NewsPublisher publisher,
            NewsFetchResult fetched,
            NewsSourceType sourceType,
            boolean overseas,
            String cursor,
            Instant nextAttempt,
            List<String> channelTopicKeys) {

        Instant collectedAt = now();
        String ingestRunId = UUID.randomUUID().toString();
        String linkRuleVersion = relevance.ruleVersion();

        int collected = 0;
        int excluded = 0;
        int duplicates = 0;
        // 등록표에 없는 분류 값은 연결에 쓰지 않는다. 원문 값은 저장하지 않고 건수만 센다(spec FR-010).
        int unmappedCategories = 0;
        Set<String> seenKeys = new HashSet<>();
        List<NewsGroupingPolicy.Candidate> candidates = new ArrayList<>();

        for (CollectedNewsItem item : fetched.items()) {
            NewsRelevanceClassifier.Relevance link =
                    relevance.classify(
                            item.sourceCategories(), overseas, channelTopicKeys, item.title());
            unmappedCategories += link.unmappedCategories().size();
            if (!link.accepted()) {
                excluded++;
                metrics.recordExcluded(
                        sourceKey,
                        com.ssafy.thorp.news.application.port.NewsMetrics.ExclusionReason
                                .NOT_RELEVANT);
                continue;
            }
            Optional<CanonicalNewsUrl> canonical = urlNormalizer.canonicalize(item.url());
            if (canonical.isEmpty()) {
                excluded++;
                metrics.recordExcluded(
                        sourceKey,
                        com.ssafy.thorp.news.application.port.NewsMetrics.ExclusionReason
                                .MALFORMED_URL);
                continue;
            }

            // 기사마다 실제 발행 매체를 먼저 정한다. 회차를 돌린 매체를 그대로 쓰면 해외 기사가 국내 매체의
            // 허가 상태를 물려받아 회신하지 않은 매체의 기사가 공개될 수 있다. 중복 열쇠도 이 매체를 기준으로
            // 만든다.
            Optional<NewsPublisher> owner =
                    resolvePublisher(item, publisher, sourceType, canonical.get());
            if (owner.isEmpty()) {
                excluded++;
                metrics.recordExcluded(
                        sourceKey,
                        com.ssafy.thorp.news.application.port.NewsMetrics.ExclusionReason
                                .NO_PUBLISHER);
                continue;
            }

            Optional<String> duplicateKey = urlNormalizer.duplicateKey(item, owner.get().getId());
            if (duplicateKey.isEmpty()) {
                excluded++;
                metrics.recordExcluded(
                        sourceKey,
                        com.ssafy.thorp.news.application.port.NewsMetrics.ExclusionReason
                                .NO_DUPLICATE_KEY);
                continue;
            }
            if (!seenKeys.add(duplicateKey.get())) {
                // 같은 회차 안에서 이미 본 기사다.
                duplicates++;
                continue;
            }

            Optional<NewsArticle> saved =
                    save(
                            item,
                            owner.get(),
                            // 항목이 밝힌 출처로 저장한다. 회차 종류로 덮으면 DOC과 배포 파일 구분이 사라진다.
                            item.sourceType(),
                            canonical.get(),
                            duplicateKey.get(),
                            collectedAt,
                            ingestRunId,
                            link,
                            linkRuleVersion);
            if (saved.isEmpty()) {
                // save가 빈 값을 주는 경우는 외부 식별자 충돌 하나다(spec FR-025).
                excluded++;
                metrics.recordExcluded(
                        sourceKey,
                        com.ssafy.thorp.news.application.port.NewsMetrics.ExclusionReason
                                .ID_COLLISION);
                continue;
            }
            collected++;
            if (!saved.get().isDisplayEligible()) {
                // 표시할 수 없는 기사는 묶음에 넣지 않는다. 대표로 뽑히면 묶음이 통째로 사라지고, 멤버로
                // 남으면 서로 다른 매체 수를 부풀려 주요도 순서를 흔든다(spec FR-001·FR-004).
                continue;
            }
            candidates.add(new NewsGroupingPolicy.Candidate(saved.get(), link.topicKeys()));
        }

        int digestsTouched = regroup(candidates, collectedAt, publisher);

        NewsCollectionState state = loadState(sourceKey);
        NewsCollectionState next =
                cursor == null && sourceType.isGdelt()
                        ? state.incomplete(collectedAt, nextAttempt)
                        : state.succeeded(cursor, collectedAt, nextAttempt);

        log.info(
                "뉴스 수집 회차 완료 source={} run={} collected={} excluded={} duplicates={} unmapped={}"
                        + " digests={}",
                sourceKey,
                ingestRunId,
                collected,
                excluded,
                duplicates,
                unmappedCategories,
                digestsTouched);

        metrics.recordIngestRun(
                sourceKey,
                java.time.Duration.between(collectedAt, now()),
                collected,
                excluded,
                duplicates,
                digestsTouched);

        return new IngestOutcome(
                sourceKey,
                collectionStates.save(next),
                collected,
                excluded,
                duplicates,
                digestsTouched);
    }

    /**
     * 응답의 ETag와 Last-Modified를 매체에 저장한다(spec FR-013).
     *
     * <p>둘 다 비어 있으면 아무것도 하지 않는다. 뉴스원이 조건부 요청을 지원하지 않는 경우다.
     */
    private void rememberConditionalRequestState(NewsPublisher publisher, NewsFetchResult fetched) {
        if (fetched.etag() == null && fetched.lastModified() == null) {
            return;
        }
        if (java.util.Objects.equals(publisher.getEtag(), fetched.etag())
                && java.util.Objects.equals(publisher.getLastModified(), fetched.lastModified())) {
            return;
        }
        publishers.save(
                publisher.withConditionalRequestState(fetched.etag(), fetched.lastModified()));
    }

    /**
     * 이 기사를 실제로 발행한 매체를 정한다.
     *
     * <p>RSS는 그 채널을 가진 매체가 곧 발행 매체다. GDELT는 남의 기사를 모아 주므로 주소의 host로 매체를 가려야 한다. 회차를 돌린 매체를 그대로 쓰면
     * 해외 기사가 국내 매체의 허가 상태를 물려받아, 회신하지 않은 매체의 기사가 공개될 수 있다(spec FR-016, SC-005).
     *
     * <p>처음 보는 매체는 {@code PENDING}으로 등록한다. 회신을 받기 전까지 공개 조회에 나오지 않는다.
     */
    private Optional<NewsPublisher> resolvePublisher(
            CollectedNewsItem item,
            NewsPublisher runPublisher,
            NewsSourceType sourceType,
            CanonicalNewsUrl canonical) {
        if (sourceType == NewsSourceType.RSS) {
            return Optional.ofNullable(runPublisher);
        }
        String host = hostOf(canonical.value());
        if (host == null) {
            return Optional.empty();
        }
        return Optional.of(
                publishers
                        .findByRegisteredDomain(host)
                        .orElseGet(
                                () ->
                                        publishers.save(
                                                NewsPublisher.register(
                                                        toPublisherKey(host),
                                                        host,
                                                        item.language(),
                                                        host,
                                                        null,
                                                        false))));
    }

    private static String hostOf(String canonicalUrl) {
        try {
            return java.net.URI.create(canonicalUrl).getHost();
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** host를 매체 키 형식으로 옮긴다. 영문 소문자·숫자·하이픈만 쓰고 64자를 넘기지 않는다. */
    private static String toPublisherKey(String host) {
        String key = host.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9-]", "-");
        return key.length() > 64 ? key.substring(0, 64) : key;
    }

    /** 기사 하나를 저장하고 연결을 남긴다. 외부 식별자 충돌이면 그 항목만 버린다(spec FR-025). */
    private Optional<NewsArticle> save(
            CollectedNewsItem item,
            NewsPublisher publisher,
            NewsSourceType sourceType,
            CanonicalNewsUrl canonical,
            String duplicateKey,
            Instant collectedAt,
            String ingestRunId,
            NewsRelevanceClassifier.Relevance link,
            String linkRuleVersion) {

        PublicationTimeStatus status =
                NewsArticle.classifyPublicationTime(item.parsedPublishedAt(), collectedAt);
        // 배포 파일 레코드에는 매체가 발행한 시각이 없다. 검증된 발행 시각을 확보하지 못한 레코드는 연결·중복
        // 판정 보조로만 쓰고 표시 후보로 만들지 않는다(plan "수집과 재시도").
        boolean displayEligible =
                sourceType != NewsSourceType.GDELT_FILE
                        && summaries
                                .summarize(
                                        new NewsSummaryPolicy.Input(
                                                item.title(),
                                                publisher.getDisplayName(),
                                                link.topicKeys(),
                                                1))
                                .isPresent();

        NewsArticle article =
                NewsArticle.collected(
                        sourceType,
                        publisher.getId(),
                        item.sourceItemId(),
                        item.url() == null ? canonical.value() : item.url(),
                        canonical,
                        item.title(),
                        item.language(),
                        status == PublicationTimeStatus.VERIFIED ? item.parsedPublishedAt() : null,
                        status,
                        collectedAt,
                        item.recordedAt(),
                        duplicateKey,
                        sourcePayloadHash(item),
                        ingestRunId,
                        displayEligible);

        NewsArticle stored;
        try {
            stored = articles.upsertByCanonicalUrl(article);
        } catch (NewsArticleRepository.NewsArticleIdCollisionException e) {
            log.warn(
                    "외부 식별자 충돌로 기사를 제외한다 publisher={} category={}",
                    publisher.getPublisherKey(),
                    NewsCollectionState.ID_COLLISION);
            return Optional.empty();
        }

        // 주제마다 어떤 규칙 항목이 걸려 연결됐는지 함께 남긴다. 근거가 없으면 나중에 연결을 따질 수 없다.
        // 기사 본문 조각은 넣지 않는다. 주제 키 자체가 근거다(spec FR-010).
        java.util.Map<Long, String> topicEvidence = new java.util.LinkedHashMap<>();
        for (String topicKey : link.topicKeys()) {
            topicCatalog
                    .findTopic(topicKey)
                    .ifPresent(
                            topic ->
                                    topicEvidence.put(
                                            links.ensureTopic(
                                                    topic.topicKey(),
                                                    topic.displayName(),
                                                    topic.domesticLink(),
                                                    linkRuleVersion),
                                            // 기사가 준 분류 값이거나 "channel"이다. 주제 키를 다시
                                            // 적으면 근거가 결론을 되풀이할 뿐이라 아무것도 말해 주지
                                            // 않는다(원칙 VIII).
                                            link.evidenceByTopicKey()
                                                    .getOrDefault(
                                                            topic.topicKey(), topic.topicKey())));
        }
        // 등록표가 제안한 섹터 중 spec 001에 실재하는 것만 연결한다. 없는 섹터로 연결하면 어떤 조회에도
        // 걸리지 않는 연결이 저장소에 남는다(spec FR-005).
        links.replaceLinks(
                stored.getId(),
                sectorLinks.resolve(link.sectorIds()),
                topicEvidence,
                linkRuleVersion);
        return Optional.of(stored);
    }

    /**
     * 이번 회차가 건드린 기사가 속한 묶음만 다시 만든다.
     *
     * <p>전체를 다시 묶으면 회차마다 비용이 커지고, 건드리지 않은 묶음의 식별자까지 흔들릴 수 있다.
     *
     * <p><b>후보에는 묶음 시간 조건만큼의 기존 기사를 함께 넣는다.</b> 이번 회차 기사끼리만 묶으면, 같은 사건 보도가 서로 다른 회차에 들어왔을 때 영영 묶이지
     * 않는다. 수집이 15분마다 도는데 같은 사건이 한 회차에 모두 들어오는 일은 드물어서, 이 범위가 없으면 묶음 기능이 사실상 동작하지 않는다.
     */
    private int regroup(
            List<NewsGroupingPolicy.Candidate> touched,
            Instant collectedAt,
            NewsPublisher publisher) {
        if (touched.isEmpty()) {
            return 0;
        }
        List<NewsGroupingPolicy.Candidate> candidates = withRecentArticles(touched, collectedAt);
        List<List<NewsGroupingPolicy.Candidate>> groups =
                grouping.group(candidates, NewsGroupingPolicy.RULE_VERSION);

        // 이번 회차가 건드린 기사가 속한 묶음만 저장한다. 나머지는 이미 저장돼 있고 구성도 그대로다.
        Set<Long> touchedIds =
                touched.stream()
                        .map(candidate -> candidate.article().getId())
                        .collect(java.util.stream.Collectors.toSet());
        groups =
                groups.stream()
                        .filter(
                                group ->
                                        group.stream()
                                                .anyMatch(
                                                        candidate ->
                                                                touchedIds.contains(
                                                                        candidate
                                                                                .article()
                                                                                .getId())))
                        .toList();

        // 재생성 범위의 옛 묶음을 지운다. 대표 기사가 바뀌면 식별자도 바뀌므로 지우지 않으면 같은 사건이 두 번 보인다.
        digests.deleteByArticleIds(
                groups.stream()
                        .flatMap(List::stream)
                        .map(candidate -> candidate.article().getId())
                        .distinct()
                        .toList());

        int saved = 0;
        for (List<NewsGroupingPolicy.Candidate> group : groups) {
            List<NewsArticle> members =
                    group.stream().map(NewsGroupingPolicy.Candidate::article).toList();
            Set<String> topicKeys = new LinkedHashSet<>();
            group.forEach(candidate -> topicKeys.addAll(candidate.topicKeys()));

            // 기사별 주제를 묶음에 실어, 왜 이 기사가 대표 기사와 같은 사건으로 묶였는지 남긴다(원칙 VIII).
            java.util.Map<Long, Set<String>> topicKeysByArticleId = new java.util.HashMap<>();
            group.forEach(
                    candidate ->
                            topicKeysByArticleId.put(
                                    candidate.article().getId(), candidate.topicKeys()));

            NewsArticle representative = NewsDigest.selectRepresentative(members).orElse(null);
            if (representative == null) {
                continue;
            }
            // 요약의 매체명은 대표 기사를 낸 매체다. 회차를 돌린 매체를 쓰면 여러 매체가 섞인 묶음에서
            // 대표 기사와 다른 이름이 문장에 실린다(spec FR-003).
            Optional<String> summary =
                    summaries.summarize(
                            new NewsSummaryPolicy.Input(
                                    representative.getTitle(),
                                    displayNameOf(representative, publisher),
                                    topicKeys,
                                    (int)
                                            members.stream()
                                                    .map(NewsArticle::getPublisherId)
                                                    .distinct()
                                                    .count()));
            if (summary.isEmpty()) {
                // 요약 근거가 부족하면 표시 후보로 만들지 않는다(spec FR-003).
                continue;
            }
            digests.save(
                    NewsDigest.of(
                            members,
                            summary.get(),
                            NewsGroupingPolicy.RULE_VERSION,
                            NewsImportancePolicy.RULE_VERSION,
                            topicKeysByArticleId));
            saved++;
        }
        return saved;
    }

    /**
     * 묶음 후보에 최근 기사를 더한다.
     *
     * <p>범위는 묶음 규칙의 시간 조건과 같다. 그보다 넓히면 어차피 시간 조건에서 걸러지고 비용만 든다.
     */
    private List<NewsGroupingPolicy.Candidate> withRecentArticles(
            List<NewsGroupingPolicy.Candidate> touched, Instant collectedAt) {
        Set<Long> touchedIds =
                touched.stream()
                        .map(candidate -> candidate.article().getId())
                        .collect(java.util.stream.Collectors.toSet());

        List<NewsArticle> recent =
                articles
                        .findDisplayEligibleSince(
                                collectedAt.minus(NewsGroupingPolicy.MAX_TIME_GAP))
                        .stream()
                        .filter(article -> !touchedIds.contains(article.getId()))
                        .toList();
        if (recent.isEmpty()) {
            return touched;
        }

        java.util.Map<Long, Set<String>> topicsByArticle =
                links.findTopicKeysByArticleIds(recent.stream().map(NewsArticle::getId).toList());

        List<NewsGroupingPolicy.Candidate> candidates = new ArrayList<>(touched);
        for (NewsArticle article : recent) {
            Set<String> topicKeys = topicsByArticle.get(article.getId());
            if (topicKeys == null || topicKeys.isEmpty()) {
                // 주제가 없으면 공통 주제 조건을 만족할 수 없어 어차피 묶이지 않는다.
                continue;
            }
            candidates.add(new NewsGroupingPolicy.Candidate(article, topicKeys));
        }
        return candidates;
    }

    /**
     * 그 기사를 낸 매체의 이름. 찾지 못하면 회차를 돌린 매체로 물러선다.
     *
     * <p>GDELT 회차에는 회차 매체가 없어 {@code fallback}이 null이다. 그때 이름을 찾지 못하면 null을 돌려주고, 요약 규칙이 근거 부족으로 보고
     * 표시 후보에서 뺀다(spec FR-003).
     */
    private String displayNameOf(NewsArticle article, NewsPublisher fallback) {
        if (fallback != null && article.getPublisherId().equals(fallback.getId())) {
            return fallback.getDisplayName();
        }
        return publishers
                .findById(article.getPublisherId())
                .map(NewsPublisher::getDisplayName)
                .orElseGet(() -> fallback == null ? null : fallback.getDisplayName());
    }

    /**
     * 뉴스원이 준 값에서 안정된 해시를 만든다(원칙 VIII).
     *
     * <p>{@code hashCode}를 쓰면 안 된다. 32비트라 충돌이 잦고 JVM이 바뀌면 값이 달라질 수 있어, 나중에 같은 응답인지 대조하는 용도로 쓸 수 없다.
     *
     * <p>기사 본문은 애초에 수집하지 않으므로 여기 들어갈 일이 없다.
     */
    private static String sourcePayloadHash(CollectedNewsItem item) {
        String businessKey =
                String.join(
                        "",
                        String.valueOf(item.sourceItemId()),
                        String.valueOf(item.url()),
                        item.title(),
                        String.valueOf(item.language()),
                        String.valueOf(item.parsedPublishedAt()),
                        String.valueOf(item.recordedAt()),
                        String.join(",", item.sourceCategories()));
        try {
            byte[] digest =
                    java.security.MessageDigest.getInstance("SHA-256")
                            .digest(businessKey.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256을 쓸 수 없다", e);
        }
    }

    /**
     * 한 회차 안에서 제한된 횟수만큼 다시 부른다(spec FR-013, plan "수집과 재시도").
     *
     * <p>일시 실패만 다시 시도한다. 401·403 같은 설정 오류는 다시 불러도 같은 답이 오므로 바로 던진다.
     *
     * <p>기다리는 동안 트랜잭션을 들고 있지 않다. 이 메서드는 트랜잭션 밖에서만 불린다 — 안에서 부르면 느린 뉴스원 하나가 커넥션을 잡은 채 잠든다.
     *
     * <p>대기 중 인터럽트가 오면 더 시도하지 않고 마지막 실패를 그대로 던진다. 종료 신호를 삼키고 계속 도는 쪽이 더 나쁘다.
     */
    private NewsFetchResult withRetry(
            String sourceKey, java.util.function.Supplier<NewsFetchResult> fetch) {
        NewsSourceClient.NewsSourceException last = null;
        for (int attempt = 1; attempt <= NewsRetryPolicy.MAX_ATTEMPTS_PER_RUN; attempt++) {
            try {
                return fetch.get();
            } catch (NewsSourceClient.NewsSourceException e) {
                last = e;
                java.util.Optional<java.time.Duration> delay = retries.delayBeforeRetry(attempt, e);
                if (delay.isEmpty()) {
                    throw e;
                }
                metrics.recordRetry(sourceKey);
                log.warn(
                        "뉴스원 호출을 다시 시도한다 source={} attempt={} category={}",
                        sourceKey,
                        attempt,
                        e.errorCategory());
                try {
                    Thread.sleep(delay.get().toMillis());
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
        }
        throw last;
    }

    private IngestOutcome failed(String sourceKey, NewsSourceClient.NewsSourceException e) {
        NewsCollectionState state = loadState(sourceKey);
        // 연속 실패가 쌓이면 다음 회차를 뒤로 민다. 죽은 뉴스원을 15분마다 두드리지 않는다.
        java.time.Duration cooldown =
                retries.cooldown(COLLECTION_INTERVAL, state.getRetryCount() + 1, e);
        NewsCollectionState next =
                state.failed(
                        e.isRetryable()
                                ? com.ssafy.thorp.news.domain.vo.NewsCollectionStatus.RATE_LIMITED
                                : com.ssafy.thorp.news.domain.vo.NewsCollectionStatus.FAILED,
                        e.errorCategory(),
                        now(),
                        now().plus(cooldown));
        if (!e.isRetryable()) {
            // 설정 오류다. 사람이 등록 정보를 고치기 전에는 달라지지 않는다(plan "수집과 재시도").
            log.warn("뉴스원을 비활성화 후보로 남긴다 source={} category={}", sourceKey, e.errorCategory());
        }
        metrics.recordIngestFailure(sourceKey, e.errorCategory());
        log.warn("뉴스 수집 실패 source={} category={}", sourceKey, e.errorCategory());
        return new IngestOutcome(sourceKey, collectionStates.save(next), 0, 0, 0, 0);
    }

    /**
     * 한 회차의 저장을 하나의 트랜잭션으로 감싼다.
     *
     * <p>{@code @Transactional}을 쓰지 않는 이유는 같은 클래스 안에서 부르면 프록시를 거치지 않아 트랜잭션이 걸리지 않기 때문이다. 그러면 수집 위치만
     * 전진하고 기사는 롤백되는 상태가 생길 수 있다.
     */
    private IngestOutcome inTransaction(java.util.function.Supplier<IngestOutcome> work) {
        return transactionTemplate.execute(status -> work.get());
    }

    private NewsCollectionState loadState(String sourceKey) {
        return collectionStates
                .findBySourceKey(sourceKey)
                .orElseGet(() -> NewsCollectionState.idle(sourceKey));
    }

    private Instant now() {
        return clock.instant();
    }

    private Instant nextAttempt() {
        return now().plus(Duration.ofMinutes(15));
    }

    /** GDELT 수집 위치. 다음 회차가 이어받을 지점이다. */
    static final class GdeltCursor {
        private GdeltCursor() {}

        static String of(Instant windowEnd) {
            return windowEnd.toString();
        }
    }
}
