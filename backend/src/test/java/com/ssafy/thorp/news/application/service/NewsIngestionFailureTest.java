package com.ssafy.thorp.news.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.ssafy.thorp.news.application.port.GdeltNewsSource;
import com.ssafy.thorp.news.application.port.NewsSourceClient;
import com.ssafy.thorp.news.application.port.RssNewsSource;
import com.ssafy.thorp.news.application.repository.NewsCollectionStateRepository;
import com.ssafy.thorp.news.application.result.NewsFetchResult;
import com.ssafy.thorp.news.domain.model.NewsCollectionState;
import com.ssafy.thorp.news.domain.model.NewsPublisher;
import com.ssafy.thorp.news.domain.vo.NewsCollectionStatus;
import com.ssafy.thorp.news.domain.vo.PermissionStatus;
import com.ssafy.thorp.news.support.RecordingNewsMetrics;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 수집이 실패했을 때 회차가 어떻게 끝나는지(spec FR-013·FR-014·FR-024, T034).
 *
 * <p>확인하는 것은 넷이다 — 일시 실패를 회차 안에서 다시 시도하는지, 설정 오류는 그러지 않는지, 실패가 수집 위치와 마지막 성공 시각을 건드리지 않는지, 그리고 한
 * 뉴스원의 실패가 다른 뉴스원으로 번지지 않는지.
 *
 * <p>쓰지 않는 협력자는 {@code null}로 둔다. 실패 경로는 기사·묶음 저장에 닿지 않으므로, 닿는 순간 터져서 시험이 조용히 다른 길을 타는 일이 없다.
 */
class NewsIngestionFailureTest {

    private static final Instant NOW = Instant.parse("2026-09-10T06:00:00Z");
    private static final String RSS_SOURCE = "rss:example-press";
    private static final String OTHER_SOURCE = "rss:other-press";

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

    /** 수집 상태만 기억하는 저장소 대역. */
    private static final class StateStore implements NewsCollectionStateRepository {
        private final Map<String, NewsCollectionState> states = new HashMap<>();

        @Override
        public NewsCollectionState save(NewsCollectionState state) {
            states.put(state.getSourceKey(), state);
            return state;
        }

        @Override
        public Optional<NewsCollectionState> findBySourceKey(String sourceKey) {
            return Optional.ofNullable(states.get(sourceKey));
        }

        @Override
        public List<NewsCollectionState> findDue(Instant now) {
            return List.of();
        }

        @Override
        public List<NewsCollectionState> findAll() {
            return List.copyOf(states.values());
        }

        @Override
        public Optional<Instant> findLatestSuccessAt(boolean publicOnly) {
            return Optional.empty();
        }
    }

    /** 정해진 횟수만큼 실패한 뒤 성공하는 뉴스원. */
    private static final class FlakySourceClient implements NewsSourceClient {
        private final NewsSourceException failure;
        private final int failuresBeforeSuccess;
        private final List<String> calls = new ArrayList<>();

        private FlakySourceClient(NewsSourceException failure, int failuresBeforeSuccess) {
            this.failure = failure;
            this.failuresBeforeSuccess = failuresBeforeSuccess;
        }

        @Override
        public NewsFetchResult fetch(RssNewsSource source) {
            calls.add(source.sourceKey());
            if (calls.size() <= failuresBeforeSuccess) {
                throw failure;
            }
            return NewsFetchResult.notModified(null, null);
        }

        @Override
        public NewsFetchResult fetch(GdeltNewsSource source) {
            return NewsFetchResult.complete(List.of(), null, null);
        }
    }

    private static NewsIngestionService service(
            NewsSourceClient client, StateStore states, RecordingNewsMetrics metrics) {
        return new NewsIngestionService(
                client,
                null,
                null,
                null,
                null,
                states,
                null,
                null,
                null,
                null,
                null,
                null,
                new NewsRetryPolicy(new Random(7)),
                metrics,
                null,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static RssNewsSource rssSource() {
        return new RssNewsSource(
                1L, "example-press", "https://rss.example.co.kr/economy.xml", null, null);
    }

    @Test
    @DisplayName("일시 실패는 회차 안에서 다시 시도하고 성공하면 그대로 끝낸다")
    void 일시_실패_복구() {
        FlakySourceClient client =
                new FlakySourceClient(
                        new NewsSourceClient.NewsSourceException("SERVER_ERROR", true), 1);
        StateStore states = new StateStore();
        RecordingNewsMetrics metrics = new RecordingNewsMetrics();

        NewsIngestionService.IngestOutcome outcome =
                service(client, states, metrics).ingest(rssSource(), PUBLISHER);

        assertThat(client.calls).as("한 번 실패했으면 회차 안에서 한 번 더 부른다").hasSize(2);
        assertThat(outcome.state().getStatus()).isEqualTo(NewsCollectionStatus.SUCCEEDED);
        assertThat(metrics.retries).containsExactly(RSS_SOURCE);
        assertThat(metrics.failures).as("결국 성공했으므로 실패로 세지 않는다").isEmpty();
    }

    @Test
    @DisplayName("설정 오류는 다시 시도하지 않고 바로 끝낸다")
    void 설정_오류는_즉시_중단() {
        FlakySourceClient client =
                new FlakySourceClient(
                        new NewsSourceClient.NewsSourceException("UNAUTHORIZED", false), 99);
        StateStore states = new StateStore();
        RecordingNewsMetrics metrics = new RecordingNewsMetrics();

        NewsIngestionService.IngestOutcome outcome =
                service(client, states, metrics).ingest(rssSource(), PUBLISHER);

        assertThat(client.calls).as("다시 불러도 같은 답이 온다").hasSize(1);
        assertThat(outcome.state().getStatus()).isEqualTo(NewsCollectionStatus.FAILED);
        assertThat(outcome.state().getErrorCategory()).isEqualTo("UNAUTHORIZED");
        assertThat(metrics.failures).containsExactly(RSS_SOURCE + "|UNAUTHORIZED");
    }

    @Test
    @DisplayName("실패는 수집 위치와 마지막 성공 시각을 건드리지 않는다")
    void 실패는_위치를_밀지_않는다() {
        StateStore states = new StateStore();
        Instant earlierSuccess = NOW.minus(Duration.ofHours(1));
        states.save(
                NewsCollectionState.idle(RSS_SOURCE).succeeded("cursor-1", earlierSuccess, NOW));

        FlakySourceClient client =
                new FlakySourceClient(
                        new NewsSourceClient.NewsSourceException("UNAUTHORIZED", false), 99);

        NewsIngestionService.IngestOutcome outcome =
                service(client, states, new RecordingNewsMetrics()).ingest(rssSource(), PUBLISHER);

        assertThat(outcome.state().getCursor())
                .as("위치가 밀리면 그 구간의 기사가 영구히 누락된다")
                .isEqualTo("cursor-1");
        assertThat(outcome.state().getLastSuccessAt()).isEqualTo(earlierSuccess);
    }

    @Test
    @DisplayName("연속 실패가 쌓이면 다음 시도를 더 뒤로 민다")
    void 연속_실패_냉각() {
        StateStore states = new StateStore();
        FlakySourceClient client =
                new FlakySourceClient(
                        new NewsSourceClient.NewsSourceException("SERVER_ERROR", true), 99);
        NewsIngestionService service = service(client, states, new RecordingNewsMetrics());

        Instant firstNext = service.ingest(rssSource(), PUBLISHER).state().getNextAttemptAt();
        Instant secondNext = service.ingest(rssSource(), PUBLISHER).state().getNextAttemptAt();

        assertThat(secondNext).as("죽은 뉴스원을 15분마다 두드리지 않는다").isAfter(firstNext);
    }

    @Test
    @DisplayName("한 뉴스원의 실패가 다른 뉴스원의 상태를 바꾸지 않는다")
    void 뉴스원_격리() {
        StateStore states = new StateStore();
        Instant otherSuccess = NOW.minus(Duration.ofMinutes(5));
        states.save(
                NewsCollectionState.idle(OTHER_SOURCE)
                        .succeeded("cursor-other", otherSuccess, NOW));

        FlakySourceClient client =
                new FlakySourceClient(
                        new NewsSourceClient.NewsSourceException("TIMEOUT", true), 99);
        service(client, states, new RecordingNewsMetrics()).ingest(rssSource(), PUBLISHER);

        NewsCollectionState other = states.findBySourceKey(OTHER_SOURCE).orElseThrow();
        assertThat(other.getStatus()).isEqualTo(NewsCollectionStatus.SUCCEEDED);
        assertThat(other.getLastSuccessAt()).isEqualTo(otherSuccess);
    }
}
