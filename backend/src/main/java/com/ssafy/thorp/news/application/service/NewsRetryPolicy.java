package com.ssafy.thorp.news.application.service;

import com.ssafy.thorp.news.application.port.NewsSourceClient;
import java.time.Duration;
import java.util.Optional;
import java.util.Random;
import java.util.random.RandomGenerator;
import org.springframework.stereotype.Component;

/**
 * 수집 실패를 어떻게 다시 시도할지 정한다(spec FR-013, plan "수집과 재시도").
 *
 * <p>분류는 두 가지다.
 *
 * <ul>
 *   <li><b>일시</b> — 제한 시간 초과, 연결 실패, 429, 503, 그 밖의 5xx. 조금 뒤에 다시 부르면 될 수 있다
 *   <li><b>설정</b> — 401·403. 다시 불러도 같은 답이 온다. 재시도하지 않고 비활성화 후보로 남긴다
 * </ul>
 *
 * <p>일시 실패의 간격은 <b>매체가 밝힌 값이 우선</b>이다. {@code Retry-After}를 준 매체에 우리 계산으로 더 빨리 가면 차단당한다. 밝히지 않았으면
 * 시도 횟수만큼 배로 늘리며 흔들림을 섞는다.
 *
 * <p><b>흔들림이 필요한 이유.</b> 등록 매체 여러 곳이 같은 순간 같은 장애를 만나면(공용 CDN 장애 같은) 재시도 시각이 한 점에 겹친다. 같은 값으로 물러섰다가
 * 같은 값으로 돌아오면 장애가 풀리는 순간 모두가 한꺼번에 몰려 방금 살아난 쪽을 다시 밀어 버린다.
 *
 * <p>한 회차에서 {@link #MAX_ATTEMPTS_PER_RUN}번까지만 시도한다. 그 뒤에는 다음 회차로 넘긴다 — 한 뉴스원 때문에 회차 전체가 길어지면 다른
 * 뉴스원의 수집이 밀린다.
 */
@Component
public class NewsRetryPolicy {

    /** 한 회차 안에서 같은 뉴스원을 부를 최대 횟수. 첫 시도를 포함한다. */
    public static final int MAX_ATTEMPTS_PER_RUN = 3;

    /** 첫 재시도까지 기다리는 기본 시간. */
    public static final Duration BASE_DELAY = Duration.ofSeconds(1);

    /**
     * 한 번에 기다릴 최대 시간.
     *
     * <p>매체가 {@code Retry-After}로 한 시간을 달라고 해도 회차 안에서 그만큼 붙잡고 있지 않는다. 이 값으로 자르고 나머지는 다음 회차로 넘긴다.
     */
    public static final Duration MAX_DELAY = Duration.ofSeconds(30);

    private final RandomGenerator random;

    /**
     * 지터용 난수원으로 {@link Random}을 쓴다. {@code java.base}에 있어 어떤 런타임에도 존재한다.
     *
     * <p><b>{@code RandomGenerator.getDefault()}로 되돌리지 않는다.</b> 그것이 고르는 기본 알고리즘 {@code
     * L32X64MixRandom}의 구현은 {@code jdk.random} 모듈에 있는데, 운영 런타임 이미지 {@code eclipse-temurin:21-jre}에는
     * 그 모듈이 없어 이 빈을 만드는 단계에서 애플리케이션 전체가 기동에 실패한다(2026-09-15 운영 배포 중단). 빌드와 테스트는 {@code 21-jdk}에서 돌아
     * {@code jdk.random}이 있으므로 CI로는 드러나지 않는다.
     *
     * <p>쓰임이 재시도 간격의 흔들림이라 암호학적 강도가 필요 없다. 난수원을 바꿀 때는 그 구현이 운영 런타임에 있는 모듈인지 먼저 확인한다.
     */
    public NewsRetryPolicy() {
        this(new Random());
    }

    /** 시험에서 흔들림을 고정하기 위한 생성자. */
    public NewsRetryPolicy(RandomGenerator random) {
        this.random = random;
    }

    /**
     * 이 실패를 회차 안에서 다시 시도할지, 한다면 얼마나 기다릴지.
     *
     * @param attempt 방금 끝난 시도의 번호. 첫 시도가 1이다.
     * @return 다시 시도하지 않으면 빈 값.
     */
    public Optional<Duration> delayBeforeRetry(
            int attempt, NewsSourceClient.NewsSourceException failure) {
        if (!failure.isRetryable() || attempt >= MAX_ATTEMPTS_PER_RUN) {
            return Optional.empty();
        }
        return Optional.of(
                failure.retryAfter().map(NewsRetryPolicy::cap).orElseGet(() -> backoff(attempt)));
    }

    /**
     * 회차 전체가 실패했을 때 다음 회차를 언제 시작할지.
     *
     * <p>기본은 평소 수집 간격이다. 매체가 그보다 더 기다리라고 밝혔으면 그 값을 쓴다 — 회차 안에서 다 못 기다린 {@code Retry-After}가 여기서
     * 이어진다. 연속 실패가 쌓이면 간격을 배로 늘려, 죽은 뉴스원을 15분마다 두드리지 않는다.
     *
     * @param consecutiveFailures 이번 실패를 포함한 연속 실패 횟수.
     */
    public Duration cooldown(
            Duration collectionInterval,
            int consecutiveFailures,
            NewsSourceClient.NewsSourceException failure) {
        Duration requested = failure.retryAfter().orElse(Duration.ZERO);
        Duration backoff = collectionInterval.multipliedBy(multiplier(consecutiveFailures));
        Duration chosen = backoff.compareTo(requested) >= 0 ? backoff : requested;
        return chosen.compareTo(maxCooldown(collectionInterval)) > 0
                ? maxCooldown(collectionInterval)
                : chosen;
    }

    /** 연속 실패가 쌓여도 이보다 오래 쉬지 않는다. 장애가 풀렸는데 한참 못 돌아오면 자료 구멍이 커진다. */
    private static Duration maxCooldown(Duration collectionInterval) {
        return collectionInterval.multipliedBy(8);
    }

    private static long multiplier(int consecutiveFailures) {
        int exponent = Math.max(0, Math.min(consecutiveFailures - 1, 3));
        return 1L << exponent;
    }

    /** 시도 횟수만큼 배로 늘린 간격에 0~100%의 흔들림을 섞는다. */
    private Duration backoff(int attempt) {
        long base = BASE_DELAY.toMillis() * (1L << Math.max(0, attempt - 1));
        long jitter = base <= 0 ? 0 : random.nextLong(base + 1);
        return cap(Duration.ofMillis(base + jitter));
    }

    private static Duration cap(Duration delay) {
        if (delay.isNegative()) {
            return Duration.ZERO;
        }
        return delay.compareTo(MAX_DELAY) > 0 ? MAX_DELAY : delay;
    }
}
