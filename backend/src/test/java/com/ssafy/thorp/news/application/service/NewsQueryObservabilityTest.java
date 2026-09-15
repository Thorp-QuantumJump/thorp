package com.ssafy.thorp.news.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.ssafy.thorp.news.application.port.NewsMetrics;
import com.ssafy.thorp.news.infrastructure.config.MicrometerNewsMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 운영 지표가 계획한 이름과 label로 남는지(spec FR-021·FR-023, T077·T078).
 *
 * <p>지표 이름은 대시보드와 경보가 참조하는 계약이다. 이름이 바뀌면 그래프가 빈 채로 남고, 그 사실을 아무도 모른다.
 *
 * <p>label 가림은 특히 중요하다. 지표는 로그보다 오래 남고 외부 수집기로 나가므로, 여기 주소가 한 번 실리면 회수할 방법이 없다(SC-009).
 */
class NewsQueryObservabilityTest {

    private final MeterRegistry registry = new SimpleMeterRegistry();
    private final NewsMetrics metrics = new MicrometerNewsMetrics(registry);

    @Test
    @DisplayName("제외 사유가 label로 갈린다")
    void 제외_사유별_집계() {
        metrics.recordExcluded("rss:example", NewsMetrics.ExclusionReason.NOT_RELEVANT);
        metrics.recordExcluded("rss:example", NewsMetrics.ExclusionReason.NOT_RELEVANT);
        metrics.recordExcluded("rss:example", NewsMetrics.ExclusionReason.ID_COLLISION);

        assertThat(counter("news.ingest.excluded", "reason", "NOT_RELEVANT")).isEqualTo(2.0);
        assertThat(counter("news.ingest.excluded", "reason", "ID_COLLISION"))
                .as("등록표를 손볼 일과 코드를 볼 일이 한 숫자에 섞이면 어느 쪽인지 알 수 없다")
                .isEqualTo(1.0);
    }

    @Test
    @DisplayName("사유 자리에 분류 코드가 아닌 값이 오면 접는다")
    void 위험한_label을_접는다() {
        metrics.recordExcluded("rss:example", "https://news.example.com/1?token=secret");

        assertThat(counter("news.ingest.excluded", "reason", "UNKNOWN"))
                .as("지표는 로그보다 오래 남고 외부 수집기로 나간다")
                .isEqualTo(1.0);
    }

    @Test
    @DisplayName("마지막 성공 경과 시간을 뉴스원별로 남긴다")
    void 마지막_성공_경과() {
        metrics.recordLastSuccessAge("rss:example", Optional.of(Duration.ofMinutes(20)));

        assertThat(gauge("news.source.last_success.age", "rss:example")).isEqualTo(1200.0);
    }

    @Test
    @DisplayName("같은 뉴스원을 다시 기록하면 값이 갱신된다")
    void 값_갱신() {
        metrics.recordLastSuccessAge("rss:example", Optional.of(Duration.ofMinutes(20)));
        metrics.recordLastSuccessAge("rss:example", Optional.of(Duration.ofMinutes(35)));

        assertThat(registry.find("news.source.last_success.age").gauges())
                .as("매번 새로 등록하면 먼저 등록된 것이 계속 옛 값을 내보낸다")
                .hasSize(1);
        assertThat(gauge("news.source.last_success.age", "rss:example")).isEqualTo(2100.0);
    }

    @Test
    @DisplayName("한 번도 성공하지 못한 뉴스원은 음수로 구별한다")
    void 성공_이력_없음() {
        metrics.recordLastSuccessAge("rss:never", Optional.empty());

        assertThat(gauge("news.source.last_success.age", "rss:never"))
                .as("0으로 두면 '방금 성공했다'와 구별되지 않아 경보가 거꾸로 선다")
                .isNegative();
    }

    @Test
    @DisplayName("조회는 상태별로 센다")
    void 조회_상태별() {
        metrics.recordQuery("READY", Duration.ofMillis(30));
        metrics.recordQuery("EMPTY", Duration.ofMillis(10));

        assertThat(counter("news.query.responses", "state", "READY")).isEqualTo(1.0);
        assertThat(counter("news.query.responses", "state", "EMPTY")).isEqualTo(1.0);
        assertThat(registry.find("news.query.duration").timer()).isNotNull();
    }

    private double counter(String name, String tagKey, String tagValue) {
        return registry.find(name).tag(tagKey, tagValue).counter().count();
    }

    private double gauge(String name, String sourceKey) {
        return registry.find(name).tag("sourceKey", sourceKey).gauge().value();
    }
}
