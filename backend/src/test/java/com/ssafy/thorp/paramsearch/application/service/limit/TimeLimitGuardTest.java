package com.ssafy.thorp.paramsearch.application.service.limit;

import static org.assertj.core.api.Assertions.assertThat;

import com.ssafy.thorp.paramsearch.application.port.SearchLimits;
import com.ssafy.thorp.paramsearch.domain.model.ParamSearch;
import com.ssafy.thorp.paramsearch.domain.model.SearchCounts;
import com.ssafy.thorp.paramsearch.domain.model.SelectionRule;
import com.ssafy.thorp.paramsearch.domain.vo.SearchSpace;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 실행시간 상한 판정(FR-005).
 *
 * <p>상한을 두지 않는 것이 지금의 설정이므로(S15P21E205-417) <b>없을 때 아무것도 막지 않는다</b>를 먼저 고정한다. 이 판정이 무너지면 사용자가 고른 조합
 * 가운데 뒤쪽이 조용히 '미실행'으로 잘리고, 화면에는 그것이 정상 완료처럼 보인다.
 */
class TimeLimitGuardTest {

    private static final Instant STARTED = Instant.parse("2026-09-23T00:00:00Z");

    @Test
    @DisplayName("상한을 두지 않으면 아무리 오래 돌아도 닿지 않는다")
    void withoutALimitNothingIsReached() {
        assertThat(guard(null, STARTED.plusSeconds(86_400)).isReached(running())).isFalse();
    }

    @Test
    @DisplayName("상한을 두면 경과 시간으로 판정한다")
    void withALimitElapsedTimeDecides() {
        assertThat(guard(600L, STARTED.plusSeconds(599)).isReached(running())).isFalse();
        assertThat(guard(600L, STARTED.plusSeconds(600)).isReached(running())).isTrue();
    }

    @Test
    @DisplayName("아직 시작하지 않은 묶음은 잴 기준이 없어 닿지 않은 것으로 본다")
    void notStartedIsNeverReached() {
        assertThat(guard(1L, STARTED.plusSeconds(86_400)).isReached(queued())).isFalse();
    }

    private static TimeLimitGuard guard(Long timeLimitSeconds, Instant now) {
        return new TimeLimitGuard(
                new SearchLimits(100, 1, timeLimitSeconds, 5, List.of()),
                Clock.fixed(now, ZoneOffset.UTC));
    }

    private static ParamSearch running() {
        return queued().markRunning(STARTED);
    }

    private static ParamSearch queued() {
        return ParamSearch.create(
                null,
                List.of("SECTOR_MOMENTUM"),
                List.of("091160.XKRX"),
                "PROVISIONAL-20260910",
                SearchSpace.of(
                        Map.of(
                                "SECTOR_MOMENTUM",
                                SearchSpace.StrategySpace.of(
                                        "섹터 모멘텀",
                                        List.of(SearchSpace.Axis.of("topN", List.of(2, 3)))))),
                selectionRule(),
                null,
                Map.of("maxCandidates", 100),
                Map.of("engineVersion", "0.1.0"),
                Map.of("initialCash", "10000000.00"),
                SearchCounts.initial(2, 2, 0));
    }

    private static SelectionRule selectionRule() {
        return new SelectionRule(
                new SelectionRule.DrawdownFilter("NOT_WORSE_THAN_BASELINE", "EQUAL_WEIGHT"),
                new SelectionRule.StabilityFilter("BATCH_PERCENTILE", 50),
                "NET_RETURN",
                5,
                "SHOW_ALL_TIED",
                new SelectionRule.StabilityFormula(
                        "SINGLE_AXIS_ONE_STEP", "MEAN_ABS_NET_RETURN_DIFF"),
                List.of("displayCount"));
    }
}
