package com.ssafy.thorp.news.infrastructure.scheduling;

import com.ssafy.thorp.news.application.port.GdeltNewsSource;
import com.ssafy.thorp.news.application.port.NewsCollectionLease;
import com.ssafy.thorp.news.application.port.NewsTopicCatalog;
import com.ssafy.thorp.news.application.port.RssNewsSource;
import com.ssafy.thorp.news.application.repository.NewsCollectionStateRepository;
import com.ssafy.thorp.news.application.repository.NewsPublisherRepository;
import com.ssafy.thorp.news.application.service.NewsIngestionService;
import com.ssafy.thorp.news.domain.model.NewsCollectionState;
import com.ssafy.thorp.news.domain.model.NewsPublisher;
import com.ssafy.thorp.news.domain.vo.NewsSourceKey;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 15분마다 실행할 때가 된 뉴스원을 골라 수집을 돌린다(spec FR-013·FR-022).
 *
 * <p><b>점유를 먼저 잡는다.</b> 서비스 인스턴스가 여러 개여도 같은 뉴스원을 한 번에 하나만 수집한다. 점유는 만료 시각을 가지므로 잡은 인스턴스가 죽어도 다음 회차가
 * 회수한다.
 *
 * <p><b>네트워크를 기다리는 동안 트랜잭션을 들고 있지 않는다.</b> 점유 획득과 저장은 각각 짧은 트랜잭션이고, 그 사이의 외부 호출은 트랜잭션 밖이다. 반대로 하면
 * 느린 뉴스원 하나가 커넥션을 오래 잡아 다른 도메인까지 막는다.
 *
 * <p>한 뉴스원의 실패는 그 뉴스원에만 머문다. 다음 뉴스원과 시장 API로 번지지 않는다(spec FR-014).
 *
 * <p><b>{@code thorp.news.collection-enabled=false}로 끌 수 있다.</b> 켜 두는 것이 기본이다. 끄는 자리는 둘이다 — 시험은 바깥
 * 망에 나가면 안 되고(나가면 그날의 실제 기사가 시험 결과를 바꾼다), 인스턴스를 늘렸을 때 수집을 한 대에만 맡기고 싶을 수 있다.
 *
 * <p>끄는 것은 수집뿐이고 조회는 그대로 돈다. 저장된 자료를 보여 주는 일과 새로 받아 오는 일은 서로 다른 책임이다.
 */
@Component
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
        name = "thorp.news.collection-enabled",
        havingValue = "true",
        matchIfMissing = true)
public class NewsCollectionScheduler {

    private static final Logger log = LoggerFactory.getLogger(NewsCollectionScheduler.class);

    /** GDELT 수집 위치가 없을 때 처음 훑을 구간. 너무 넓게 잡으면 첫 회차가 길어진다. */
    private static final Duration INITIAL_GDELT_WINDOW = Duration.ofHours(1);

    private final NewsPublisherRepository publishers;
    private final NewsCollectionStateRepository collectionStates;
    private final NewsCollectionLease lease;
    private final NewsTopicCatalog topics;
    private final com.ssafy.thorp.news.infrastructure.config.NewsPublisherRegistry registry;
    private final NewsIngestionService ingestion;
    private final com.ssafy.thorp.news.application.port.NewsMetrics metrics;
    private final Clock clock;

    /** 이 인스턴스를 가리키는 이름. 점유 주인으로 기록한다. */
    private final String instanceId = UUID.randomUUID().toString();

    public NewsCollectionScheduler(
            NewsPublisherRepository publishers,
            NewsCollectionStateRepository collectionStates,
            NewsCollectionLease lease,
            NewsTopicCatalog topics,
            com.ssafy.thorp.news.infrastructure.config.NewsPublisherRegistry registry,
            NewsIngestionService ingestion,
            com.ssafy.thorp.news.application.port.NewsMetrics metrics,
            Clock clock) {
        this.publishers = publishers;
        this.collectionStates = collectionStates;
        this.lease = lease;
        this.topics = topics;
        this.registry = registry;
        this.ingestion = ingestion;
        this.metrics = metrics;
        this.clock = clock;
    }

    /**
     * 수집 회차를 시작한다.
     *
     * <p>등록된 매체가 없으면 RSS 회차는 돌 것이 없지만 GDELT 회차는 그대로 돈다. 매체 등록은 공개 게이트와 별개로 진행되며, 비어 있는 동안에도 해외 보완
     * 수집은 계속돼야 한다(spec Assumptions, FR-009).
     */
    @Scheduled(fixedDelayString = "${thorp.news.collection-interval:PT15M}")
    public void collectDueSources() {
        Instant now = clock.instant();
        for (NewsPublisher publisher : publishers.findEnabled()) {
            runRss(publisher, now);
        }
        runGdelt(now);
        reportFreshness(now);
    }

    /**
     * 뉴스원마다 마지막 성공 이후 얼마나 지났는지 남긴다(spec FR-023).
     *
     * <p>회차가 돌 때마다 갱신한다. 실패 건수만으로는 <b>조용히 멈춘 뉴스원</b>을 찾지 못한다 — 회차 자체가 돌지 않으면 실패도 늘지 않기 때문이다. 이 값이
     * 계속 커지는 것으로만 알 수 있다.
     *
     * <p>여기서 실패해도 수집에는 영향을 주지 않는다. 지표를 남기지 못한 것 때문에 회차가 깨지면 본말이 뒤집힌다.
     */
    private void reportFreshness(Instant now) {
        try {
            for (NewsCollectionState state : collectionStates.findAll()) {
                metrics.recordLastSuccessAge(
                        state.getSourceKey(),
                        java.util.Optional.ofNullable(state.getLastSuccessAt())
                                .map(lastSuccessAt -> Duration.between(lastSuccessAt, now)));
            }
        } catch (RuntimeException e) {
            log.warn("뉴스원 신선도 지표를 남기지 못했다");
        }
    }

    private void runRss(NewsPublisher publisher, Instant now) {
        if (publisher.getRssUrl() == null) {
            return;
        }
        String sourceKey = NewsSourceKey.rss(publisher.getPublisherKey());
        if (!isDue(sourceKey, now)) {
            return;
        }
        if (lease.tryAcquire(sourceKey, instanceId, now).isEmpty()) {
            // 다른 인스턴스가 이미 이 뉴스원을 돌리고 있다.
            return;
        }
        try {
            RssNewsSource source =
                    new RssNewsSource(
                            publisher.getId(),
                            publisher.getPublisherKey(),
                            publisher.getRssUrl(),
                            publisher.getEtag(),
                            publisher.getLastModified(),
                            channelTopicKeys(publisher.getPublisherKey()));
            ingestion.ingest(source, publisher);
        } catch (RuntimeException e) {
            // 한 뉴스원의 실패는 여기서 멈춘다. 다음 뉴스원과 시장 API로 번지지 않는다.
            log.warn("RSS 수집 회차가 실패했다 source={}", sourceKey);
        } finally {
            lease.release(sourceKey, instanceId);
        }
    }

    private void runGdelt(Instant now) {
        String sourceKey = NewsSourceKey.gdelt("doc");
        if (!isDue(sourceKey, now)) {
            return;
        }
        if (lease.tryAcquire(sourceKey, instanceId, now).isEmpty()) {
            return;
        }
        try {
            // 등록된 매체가 없어도 돌린다. 해외 기사의 매체는 수집이 기사마다 host로 정해 등록한다.
            GdeltNewsSource window = nextWindow(sourceKey, now);
            ingestion.ingest(window);
        } catch (RuntimeException e) {
            log.warn("GDELT 수집 회차가 실패했다 source={}", sourceKey);
        } finally {
            lease.release(sourceKey, instanceId);
        }
    }

    /**
     * 그 채널이 선언한 주제. 등록표가 원본이다.
     *
     * <p>저장소가 아니라 등록표에서 읽는 이유는 이것이 채널 정보이기 때문이다. 매체 행에 컬럼을 더하면 같은 값이 두 곳에 생기고, 배포로만 바뀌는 값을 DB
     * 마이그레이션으로 관리하게 된다.
     *
     * <p>기사가 분류 값을 주는 매체는 이 목록이 비어 있다. 비어 있으면 보완 경로가 돌지 않는다.
     */
    private List<String> channelTopicKeys(String publisherKey) {
        return registry.publishers().stream()
                .filter(entry -> entry.publisherKey().equals(publisherKey))
                .findFirst()
                .map(
                        com.ssafy.thorp.news.infrastructure.config.NewsPublisherRegistry.Publisher
                                ::topicKeys)
                .orElseGet(List::of);
    }

    /** 수집 위치에서 이어받는다. 위치가 없으면 최근 한 시간부터 시작한다. */
    private GdeltNewsSource nextWindow(String sourceKey, Instant now) {
        Instant start =
                collectionStates
                        .findBySourceKey(sourceKey)
                        .map(NewsCollectionState::getCursor)
                        .flatMap(NewsCollectionScheduler::parseInstant)
                        .orElse(now.minus(INITIAL_GDELT_WINDOW));
        // 국내와 연결되는 주제로 조회를 좁힌다. 비워 두면 질의가 넓어지고, 받은 기사에 실을 연결 근거도 없어
        // 관련성 필터가 전량 거부한다.
        return new GdeltNewsSource(
                sourceKey, start, now, List.copyOf(topics.domesticLinkTopicKeys()));
    }

    /** 다음 시도 시각이 지났고 점유가 비었는지. 상태가 없으면 첫 회차다. */
    private boolean isDue(String sourceKey, Instant now) {
        return collectionStates
                .findBySourceKey(sourceKey)
                .map(state -> state.isDue(now))
                .orElse(true);
    }

    private static java.util.Optional<Instant> parseInstant(String value) {
        try {
            return java.util.Optional.of(Instant.parse(value));
        } catch (java.time.format.DateTimeParseException e) {
            return java.util.Optional.empty();
        }
    }
}
