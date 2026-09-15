package com.ssafy.thorp.news.infrastructure.config;

import com.ssafy.thorp.news.application.port.NewsMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import java.time.Duration;
import org.springframework.stereotype.Component;

/**
 * {@link NewsMetrics}를 Micrometer로 기록한다(plan "보관과 관측성").
 *
 * <p>지표 이름은 plan이 고정한 값이다. 이름을 바꾸면 대시보드와 경보가 말없이 빈 그래프가 되므로 여기서 상수로 묶어 둔다.
 *
 * <p><b>label 값을 그대로 넘기지 않는다.</b> 오류 분류는 대문자와 밑줄로만 이뤄진 코드만 통과시키고, 그 밖의 값은 {@code UNKNOWN}으로 접는다. 이
 * 방어가 없으면 언젠가 예외 메시지나 주소가 label로 들어가고, 그 순간 시계열이 갈라지면서 주소가 지표 수집기로 흘러 나간다(spec FR-021·SC-009).
 */
@Component
public class MicrometerNewsMetrics implements NewsMetrics {

    static final String RUN_DURATION = "news.source.run.duration";
    static final String RETRY_COUNT = "news.source.retry.count";
    static final String ERROR_COUNT = "news.source.error.count";
    static final String INGEST_ITEMS = "news.ingest.items";
    static final String DIGEST_COUNT = "news.ingest.digest.count";
    static final String EXCLUDED_COUNT = "news.ingest.excluded";
    static final String LAST_SUCCESS_AGE = "news.source.last_success.age";
    static final String QUERY_DURATION = "news.query.duration";
    static final String QUERY_RESPONSES = "news.query.responses";

    /** label로 받아들일 분류 코드의 모양. 이 밖의 값은 접는다. */
    private static final java.util.regex.Pattern SAFE_CATEGORY =
            java.util.regex.Pattern.compile("^[A-Z_]{1,64}$");

    private static final String UNKNOWN = "UNKNOWN";

    /**
     * 마지막 성공 경과 시간을 담는 그릇.
     *
     * <p>Micrometer의 gauge는 값을 밀어 넣는 것이 아니라 <b>객체를 붙들고 읽어 가는</b> 방식이다. 그래서 뉴스원마다 그릇을 하나 만들어 두고 값만
     * 갈아 끼운다. 매번 새로 등록하면 같은 이름의 gauge가 쌓이고 먼저 등록된 것이 계속 옛 값을 내보낸다.
     *
     * <p>한 번도 성공하지 못한 뉴스원은 음수를 담는다. 0으로 두면 "방금 성공했다"와 구별되지 않아 경보가 거꾸로 선다.
     *
     * <p><b>이 맵에서 항목을 빼지 않는 것은 의도다.</b> Micrometer는 gauge가 붙들 객체를 약한 참조로 잡으므로, 여기서 강하게 붙들지 않으면 객체가
     * 회수되면서 지표가 조용히 {@code NaN}이 된다. 크기는 수집 상태 행 수(등록 매체 수 + GDELT)로 묶여 있다 — GDELT 기사에서 자동 등록되는 매체는
     * {@code enabled=false}라 회차를 돌지 않아 수집 상태 행을 만들지 않는다.
     */
    private final java.util.Map<String, java.util.concurrent.atomic.AtomicLong> lastSuccessAges =
            new java.util.concurrent.ConcurrentHashMap<>();

    private final MeterRegistry registry;

    public MicrometerNewsMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void recordIngestRun(
            String sourceKey,
            Duration duration,
            int collected,
            int excluded,
            int duplicates,
            int digests) {
        Tags source = Tags.of("sourceKey", safe(sourceKey));
        registry.timer(RUN_DURATION, source).record(duration);
        registry.counter(INGEST_ITEMS, source.and("outcome", "collected")).increment(collected);
        registry.counter(INGEST_ITEMS, source.and("outcome", "excluded")).increment(excluded);
        registry.counter(INGEST_ITEMS, source.and("outcome", "duplicate")).increment(duplicates);
        registry.counter(DIGEST_COUNT, source).increment(digests);
    }

    @Override
    public void recordIngestFailure(String sourceKey, String errorCategory) {
        registry.counter(
                        ERROR_COUNT,
                        Tags.of("sourceKey", safe(sourceKey), "category", category(errorCategory)))
                .increment();
    }

    @Override
    public void recordRetry(String sourceKey) {
        registry.counter(RETRY_COUNT, Tags.of("sourceKey", safe(sourceKey))).increment();
    }

    @Override
    public void recordExcluded(String sourceKey, String reason) {
        registry.counter(
                        EXCLUDED_COUNT,
                        Tags.of("sourceKey", safe(sourceKey), "reason", category(reason)))
                .increment();
    }

    @Override
    public void recordLastSuccessAge(
            String sourceKey, java.util.Optional<Duration> sinceLastSuccess) {
        long seconds = sinceLastSuccess.map(Duration::toSeconds).orElse(-1L);
        lastSuccessAges
                .computeIfAbsent(
                        safe(sourceKey),
                        key ->
                                registry.gauge(
                                        LAST_SUCCESS_AGE,
                                        Tags.of("sourceKey", key),
                                        new java.util.concurrent.atomic.AtomicLong(seconds)))
                .set(seconds);
    }

    @Override
    public void recordQuery(String state, Duration duration) {
        Tags tags = Tags.of("state", category(state));
        registry.timer(QUERY_DURATION, tags).record(duration);
        registry.counter(QUERY_RESPONSES, tags).increment();
    }

    /**
     * 뉴스원 식별자를 label로 쓸 수 있는 모양으로 줄인다.
     *
     * <p>{@code rss:{publisherKey}} 같은 값이라 가짓수가 등록 매체 수를 넘지 않는다. 주소가 아니므로 그대로 두되, 비어 있으면 접는다.
     */
    private static String safe(String sourceKey) {
        return sourceKey == null || sourceKey.isBlank() ? UNKNOWN : sourceKey;
    }

    private static String category(String value) {
        return value != null && SAFE_CATEGORY.matcher(value).matches() ? value : UNKNOWN;
    }
}
