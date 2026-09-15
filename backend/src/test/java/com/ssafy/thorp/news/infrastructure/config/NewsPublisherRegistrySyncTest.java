package com.ssafy.thorp.news.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.ssafy.thorp.news.application.repository.NewsPublisherPermissionHistoryRepository;
import com.ssafy.thorp.news.application.repository.NewsPublisherRepository;
import com.ssafy.thorp.news.domain.model.NewsPublisher;
import com.ssafy.thorp.news.domain.model.NewsPublisherPermissionHistory;
import com.ssafy.thorp.news.domain.vo.NewsAllowedField;
import com.ssafy.thorp.news.domain.vo.PermissionStatus;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 등록표를 저장소에 반영하는 규칙(spec FR-009·FR-015).
 *
 * <p>여기서 보는 것은 <b>무엇이 무엇의 원본인가</b>이다. 채널 정보는 등록표가, 허가 상태와 조건부 요청 값은 저장소가 소유한다. 기동할 때마다 도는 코드라 이 구분이
 * 틀리면 재시작이 곧 데이터 손실이 된다.
 */
class NewsPublisherRegistrySyncTest {

    private static final Instant RESPONDED_AT = Instant.parse("2026-09-20T01:00:00Z");

    private static NewsPublisherRegistry.Publisher entry(
            NewsPublisherRegistry.Permission permission) {
        return new NewsPublisherRegistry.Publisher(
                "khan-economy",
                "경향신문",
                "ko",
                "khan.co.kr",
                "https://www.khan.co.kr/rss/rssdata/economy_news.xml",
                true,
                List.of(),
                permission);
    }

    private static NewsPublisherRegistry.Permission allowed() {
        return new NewsPublisherRegistry.Permission(
                PermissionStatus.ALLOWED,
                List.of(NewsAllowedField.TITLE, NewsAllowedField.ORIGINAL_URL),
                RESPONDED_AT,
                "2026-09-20 회신 메일");
    }

    private static Harness sync(NewsPublisherRegistry.Publisher... entries) {
        PublisherStore publishers = new PublisherStore();
        HistoryStore history = new HistoryStore(publishers);
        NewsPublisherRegistrySync target =
                new NewsPublisherRegistrySync(
                        new NewsPublisherRegistry("news-publishers-v1", List.of(entries)),
                        publishers,
                        history);
        return new Harness(target, publishers, history);
    }

    private record Harness(
            NewsPublisherRegistrySync target, PublisherStore publishers, HistoryStore history) {}

    @Test
    @DisplayName("등록표의 매체를 회신 전 상태로 만든다")
    void 새_매체는_회신_전() {
        Harness harness = sync(entry(null));

        harness.target().afterSingletonsInstantiated();

        assertThat(harness.publishers().findByPublisherKey("khan-economy"))
                .get()
                .satisfies(
                        publisher ->
                                assertThat(publisher.getPermissionStatus())
                                        .as("등록했다는 것과 공개해도 된다는 것은 다르다")
                                        .isEqualTo(PermissionStatus.PENDING));
        assertThat(harness.history().saved).isEmpty();
    }

    @Test
    @DisplayName("등록표에 적힌 회신을 이력으로 반영한다")
    void 회신_반영() {
        Harness harness = sync(entry(allowed()));

        harness.target().afterSingletonsInstantiated();

        assertThat(harness.history().saved).as("매체 행에 직접 쓰면 왜 공개됐는지 답할 근거가 남지 않는다").hasSize(1);
        assertThat(harness.history().saved.get(0).allowedFields())
                .containsExactlyInAnyOrder(NewsAllowedField.TITLE, NewsAllowedField.ORIGINAL_URL);
        assertThat(harness.publishers().findByPublisherKey("khan-economy"))
                .get()
                .satisfies(
                        publisher ->
                                assertThat(publisher.getPermissionStatus())
                                        .isEqualTo(PermissionStatus.ALLOWED));
    }

    @Test
    @DisplayName("같은 회신을 다시 기동해도 이력이 늘지 않는다")
    void 멱등() {
        Harness harness = sync(entry(allowed()));

        harness.target().afterSingletonsInstantiated();
        harness.target().afterSingletonsInstantiated();
        harness.target().afterSingletonsInstantiated();

        assertThat(harness.history().saved).as("기동할 때마다 도는 코드라 그냥 두면 재시작 횟수만큼 이력이 불어난다").hasSize(1);
    }

    @Test
    @DisplayName("같은 시각과 상태라도 허용 필드가 바뀌면 새 회신으로 기록한다")
    void 허용_필드가_바뀐_회신() {
        Harness harness = sync(entry(allowed()));
        harness.target().afterSingletonsInstantiated();

        new NewsPublisherRegistrySync(
                        new NewsPublisherRegistry(
                                "news-publishers-v1",
                                List.of(
                                        entry(
                                                new NewsPublisherRegistry.Permission(
                                                        PermissionStatus.ALLOWED,
                                                        List.of(NewsAllowedField.TITLE),
                                                        RESPONDED_AT,
                                                        "2026-09-20 정정 회신")))),
                        harness.publishers(),
                        harness.history())
                .afterSingletonsInstantiated();

        assertThat(harness.history().saved).hasSize(2);
        assertThat(harness.history().saved.get(1).allowedFields())
                .containsExactly(NewsAllowedField.TITLE);
    }

    @Test
    @DisplayName("회신 시각이 바뀌면 새 회신으로 보고 다시 넣는다")
    void 답을_바꾼_경우() {
        Harness harness = sync(entry(allowed()));
        harness.target().afterSingletonsInstantiated();

        Harness later =
                new Harness(
                        new NewsPublisherRegistrySync(
                                new NewsPublisherRegistry(
                                        "news-publishers-v1",
                                        List.of(
                                                entry(
                                                        new NewsPublisherRegistry.Permission(
                                                                PermissionStatus.DENIED,
                                                                List.of(),
                                                                RESPONDED_AT.plusSeconds(86400),
                                                                "2026-09-21 철회 회신")))),
                                harness.publishers(),
                                harness.history()),
                        harness.publishers(),
                        harness.history());
        later.target().afterSingletonsInstantiated();

        assertThat(harness.history().saved).hasSize(2);
        assertThat(harness.publishers().findByPublisherKey("khan-economy"))
                .get()
                .satisfies(
                        publisher ->
                                assertThat(publisher.getPermissionStatus())
                                        .as("매체가 답을 바꾸면 공개도 닫혀야 한다")
                                        .isEqualTo(PermissionStatus.DENIED));
    }

    @Test
    @DisplayName("이미 있는 매체는 채널 정보만 갈아 끼우고 허가는 건드리지 않는다")
    void 채널만_갱신() {
        PublisherStore publishers = new PublisherStore();
        NewsPublisher existing =
                publishers.save(
                        NewsPublisher.register(
                                        "khan-economy",
                                        "옛 이름",
                                        "ko",
                                        "khan.co.kr",
                                        "https://www.khan.co.kr/old.xml",
                                        true)
                                .withPermission(PermissionStatus.ALLOWED, RESPONDED_AT));
        HistoryStore history = new HistoryStore(publishers);

        new NewsPublisherRegistrySync(
                        new NewsPublisherRegistry("news-publishers-v1", List.of(entry(null))),
                        publishers,
                        history)
                .afterSingletonsInstantiated();

        assertThat(publishers.findByPublisherKey("khan-economy"))
                .get()
                .satisfies(
                        publisher -> {
                            assertThat(publisher.getDisplayName()).isEqualTo("경향신문");
                            assertThat(publisher.getPermissionStatus())
                                    .as("기동할 때마다 허가가 지워지면 공개가 조용히 닫힌다")
                                    .isEqualTo(PermissionStatus.ALLOWED);
                        });
        assertThat(existing.getId())
                .isEqualTo(publishers.findByPublisherKey("khan-economy").orElseThrow().getId());
    }

    /** 매체 저장소 대역. */
    private static final class PublisherStore implements NewsPublisherRepository {
        private final java.util.Map<String, NewsPublisher> byKey = new java.util.LinkedHashMap<>();
        private final AtomicLong ids = new AtomicLong();

        @Override
        public NewsPublisher save(NewsPublisher publisher) {
            Long id = publisher.getId() == null ? ids.incrementAndGet() : publisher.getId();
            NewsPublisher stored =
                    NewsPublisher.restore(
                            id,
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
            byKey.put(stored.getPublisherKey(), stored);
            return stored;
        }

        @Override
        public Optional<NewsPublisher> findById(Long id) {
            return byKey.values().stream().filter(p -> p.getId().equals(id)).findFirst();
        }

        @Override
        public Optional<NewsPublisher> findByPublisherKey(String publisherKey) {
            return Optional.ofNullable(byKey.get(publisherKey));
        }

        @Override
        public Optional<NewsPublisher> findByRegisteredDomain(String registeredDomain) {
            return Optional.empty();
        }

        @Override
        public List<NewsPublisher> findEnabled() {
            return byKey.values().stream().filter(NewsPublisher::isEnabled).toList();
        }

        @Override
        public List<NewsPublisher> findPubliclyVisible() {
            return byKey.values().stream().filter(NewsPublisher::isPubliclyVisible).toList();
        }

        @Override
        public boolean existsPubliclyVisible() {
            return !findPubliclyVisible().isEmpty();
        }
    }

    /** 허가 이력 대역. 실제 구현처럼 이력 추가와 상태 갱신을 함께 한다. */
    private static final class HistoryStore implements NewsPublisherPermissionHistoryRepository {

        private final PublisherStore publishers;
        private final List<NewsPublisherPermissionHistory> saved = new ArrayList<>();

        private HistoryStore(PublisherStore publishers) {
            this.publishers = publishers;
        }

        @Override
        public NewsPublisherPermissionHistory appendAndApply(NewsPublisherPermissionHistory event) {
            saved.add(event);
            publishers
                    .findById(event.publisherId())
                    .ifPresent(
                            publisher ->
                                    publishers.save(
                                            publisher.withPermission(
                                                    event.permissionStatus(), event.occurredAt())));
            return event;
        }

        @Override
        public List<NewsPublisherPermissionHistory> findByPublisherId(Long publisherId) {
            return saved.stream().filter(e -> e.publisherId().equals(publisherId)).toList();
        }

        @Override
        public Optional<NewsPublisherPermissionHistory> findLatestResponse(Long publisherId) {
            return findByPublisherId(publisherId).stream().reduce((a, b) -> b);
        }
    }
}
