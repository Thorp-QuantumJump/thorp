package com.ssafy.thorp.news.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.ssafy.thorp.news.application.port.NewsSourceClient;
import java.time.Duration;
import java.util.Optional;
import java.util.Random;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * 재시도 판정(spec FR-013, plan "수집과 재시도", T034).
 *
 * <p>흔들림은 고정 씨앗으로 묶는다. 무작위를 그대로 두면 시험이 가끔 실패하고, 그런 시험은 곧 아무도 믿지 않게 된다.
 */
class NewsRetryPolicyTest {

    private static final Duration INTERVAL = Duration.ofMinutes(15);

    private final NewsRetryPolicy policy = new NewsRetryPolicy(new Random(42));

    private static NewsSourceClient.NewsSourceException transientFailure() {
        return new NewsSourceClient.NewsSourceException("SERVER_ERROR", true);
    }

    private static NewsSourceClient.NewsSourceException terminalFailure() {
        return new NewsSourceClient.NewsSourceException("UNAUTHORIZED", false);
    }

    private static NewsSourceClient.NewsSourceException rateLimited(Duration retryAfter) {
        return new NewsSourceClient.NewsSourceException("RATE_LIMITED", true, retryAfter);
    }

    @Nested
    @DisplayName("회차 안 재시도")
    class WithinRun {

        @Test
        @DisplayName("설정 오류는 다시 시도하지 않는다")
        void 설정_오류() {
            assertThat(policy.delayBeforeRetry(1, terminalFailure()))
                    .as("401·403은 다시 불러도 같은 답이다. 사람이 등록 정보를 고쳐야 한다")
                    .isEmpty();
        }

        @Test
        @DisplayName("일시 실패는 정해진 횟수까지만 다시 시도한다")
        void 시도_상한() {
            assertThat(policy.delayBeforeRetry(1, transientFailure())).isPresent();
            assertThat(policy.delayBeforeRetry(2, transientFailure())).isPresent();
            assertThat(
                            policy.delayBeforeRetry(
                                    NewsRetryPolicy.MAX_ATTEMPTS_PER_RUN, transientFailure()))
                    .as("한 뉴스원 때문에 회차가 길어지면 다른 뉴스원의 수집이 밀린다")
                    .isEmpty();
        }

        @Test
        @DisplayName("매체가 밝힌 Retry-After가 우리 백오프보다 우선한다")
        void retry_after_우선() {
            Duration requested = Duration.ofSeconds(7);

            assertThat(policy.delayBeforeRetry(1, rateLimited(requested)))
                    .as("매체가 정한 간격을 우리 계산으로 덮으면 차단당한다")
                    .contains(requested);
        }

        @Test
        @DisplayName("Retry-After가 지나치게 길면 상한으로 자른다")
        void retry_after_상한() {
            Optional<Duration> delay = policy.delayBeforeRetry(1, rateLimited(Duration.ofHours(1)));

            assertThat(delay).contains(NewsRetryPolicy.MAX_DELAY);
        }

        @Test
        @DisplayName("간격은 시도마다 늘어나고 상한을 넘지 않는다")
        void 백오프() {
            Duration first = policy.delayBeforeRetry(1, transientFailure()).orElseThrow();
            Duration second = policy.delayBeforeRetry(2, transientFailure()).orElseThrow();

            assertThat(first).isBetween(NewsRetryPolicy.BASE_DELAY, NewsRetryPolicy.MAX_DELAY);
            assertThat(second).isLessThanOrEqualTo(NewsRetryPolicy.MAX_DELAY);
            assertThat(second)
                    .as("두 번째 시도의 기본 간격은 첫 번째의 두 배다")
                    .isGreaterThanOrEqualTo(NewsRetryPolicy.BASE_DELAY.multipliedBy(2));
        }

        @Test
        @DisplayName("같은 조건에서도 간격이 매번 같지는 않다")
        void 흔들림() {
            NewsRetryPolicy a = new NewsRetryPolicy(new Random(1));
            NewsRetryPolicy b = new NewsRetryPolicy(new Random(2));

            assertThat(a.delayBeforeRetry(2, transientFailure()))
                    .as("여러 매체가 같은 순간 물러섰다가 같은 순간 돌아오면 방금 살아난 쪽을 다시 민다")
                    .isNotEqualTo(b.delayBeforeRetry(2, transientFailure()));
        }
    }

    @Nested
    @DisplayName("회차 사이 냉각")
    class Cooldown {

        @Test
        @DisplayName("첫 실패는 평소 간격만큼 쉰다")
        void 첫_실패() {
            assertThat(policy.cooldown(INTERVAL, 1, transientFailure())).isEqualTo(INTERVAL);
        }

        @Test
        @DisplayName("연속 실패가 쌓이면 간격이 늘어난다")
        void 연속_실패() {
            assertThat(policy.cooldown(INTERVAL, 3, transientFailure()))
                    .as("죽은 뉴스원을 15분마다 두드리지 않는다")
                    .isGreaterThan(INTERVAL);
        }

        @Test
        @DisplayName("아무리 실패해도 상한보다 오래 쉬지 않는다")
        void 냉각_상한() {
            assertThat(policy.cooldown(INTERVAL, 100, transientFailure()))
                    .as("장애가 풀렸는데 한참 못 돌아오면 자료 구멍이 커진다")
                    .isEqualTo(INTERVAL.multipliedBy(8));
        }

        @Test
        @DisplayName("매체가 평소 간격보다 길게 요청하면 그 값을 따른다")
        void 긴_retry_after() {
            Duration requested = INTERVAL.multipliedBy(2);

            assertThat(policy.cooldown(INTERVAL, 1, rateLimited(requested))).isEqualTo(requested);
        }
    }
}
