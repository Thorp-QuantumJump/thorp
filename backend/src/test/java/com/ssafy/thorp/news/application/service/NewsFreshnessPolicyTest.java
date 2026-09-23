package com.ssafy.thorp.news.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.ssafy.thorp.news.domain.vo.NewsDigestState;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * 표시 상태 판정(spec FR-014·SC-004, T035).
 *
 * <p>시각은 고정한다. 실제 시계를 쓰면 문턱 경계를 확인할 수 없고, 빌드가 도는 순간에 따라 결과가 달라진다.
 */
class NewsFreshnessPolicyTest {

    private static final Instant NOW = Instant.parse("2026-09-10T06:15:00Z");
    private static final Duration THRESHOLD = Duration.ofMinutes(30);

    private final NewsFreshnessPolicy policy = new NewsFreshnessPolicy();

    @Nested
    @DisplayName("판정 순서")
    class Order {

        @Test
        @DisplayName("허가 매체가 없으면 자료가 있어도 PREPARING이다")
        void 허가_먼저() {
            assertThat(policy.stateOf(false, true, NOW, NOW, THRESHOLD))
                    .as("보여 줄 권한이 없는 것과 보여 줄 것이 없는 것은 다르다")
                    .isEqualTo(NewsDigestState.PREPARING);
        }

        @Test
        @DisplayName("허가 매체는 있는데 자료가 없으면 EMPTY다")
        void 자료_없음() {
            assertThat(policy.stateOf(true, false, NOW, NOW, THRESHOLD))
                    .isEqualTo(NewsDigestState.EMPTY);
        }

        @Test
        @DisplayName("오래된 자료가 있으면 STALE이다")
        void 지연() {
            Instant old = NOW.minus(Duration.ofHours(2));
            assertThat(policy.stateOf(true, true, old, NOW, THRESHOLD))
                    .isEqualTo(NewsDigestState.STALE);
        }

        @Test
        @DisplayName("최근 자료가 있으면 READY다")
        void 정상() {
            Instant recent = NOW.minus(Duration.ofMinutes(5));
            assertThat(policy.stateOf(true, true, recent, NOW, THRESHOLD))
                    .isEqualTo(NewsDigestState.READY);
        }
    }

    @Nested
    @DisplayName("문턱 경계")
    class Threshold {

        @Test
        @DisplayName("정확히 문턱만큼 지나면 지연이다")
        void 경계_포함() {
            assertThat(policy.isStale(NOW.minus(THRESHOLD), NOW, THRESHOLD)).isTrue();
        }

        @Test
        @DisplayName("문턱보다 1초 덜 지났으면 지연이 아니다")
        void 경계_직전() {
            assertThat(policy.isStale(NOW.minus(THRESHOLD).plusSeconds(1), NOW, THRESHOLD))
                    .isFalse();
        }

        @Test
        @DisplayName("한 번도 수집하지 못했으면 지연이 아니다")
        void 최초_수집_전() {
            assertThat(policy.isStale(null, NOW, THRESHOLD))
                    .as("성공한 적 없는 것과 성공했지만 오래된 것은 화면이 달리 말해야 한다")
                    .isFalse();
        }
    }

    @Nested
    @DisplayName("상태 문구")
    class Messages {

        @Test
        @DisplayName("최초 수집 전에는 '아직 수집되지 않음'이다")
        void 최초_수집_전_문구() {
            assertThat(policy.messageOf(NewsDigestState.EMPTY, null, false))
                    .isEqualTo(NewsFreshnessPolicy.NEVER_COLLECTED_MESSAGE);
        }

        @Test
        @DisplayName("수집 이력이 있는 빈 결과는 다른 문구다")
        void 수집_후_빈_결과() {
            assertThat(policy.messageOf(NewsDigestState.EMPTY, NOW, false))
                    .isEqualTo(NewsFreshnessPolicy.EMPTY_MESSAGE);
        }

        @Test
        @DisplayName("과거 구간의 빈 결과는 기간을 가리키는 문구다")
        void 과거_빈_결과() {
            assertThat(policy.messageOf(NewsDigestState.EMPTY, NOW, true))
                    .isEqualTo(NewsFreshnessPolicy.HISTORICAL_EMPTY_MESSAGE);
        }

        @Test
        @DisplayName("READY에는 덧붙일 말이 없다")
        void 정상은_문구_없음() {
            assertThat(policy.messageOf(NewsDigestState.READY, NOW, false)).isNull();
        }
    }
}
