package com.ssafy.thorp.paramsearch.application.service.limit;

import com.ssafy.thorp.paramsearch.application.port.SearchLimits;
import com.ssafy.thorp.paramsearch.domain.model.ParamSearch;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 실행시간 한도(FR-005, 내부 계약 §4 "한도 도달").
 *
 * <p><b>묶음 시작 시각부터 잰다.</b> 후보별 실행시간이 아니라 묶음 전체의 벽시계 시간이다 — 사용자가 기다리는 것은 묶음이고, 후보 하나하나가 빨라도 수백 건이면
 * 오래 걸린다.
 *
 * <p><b>넘겼다고 실행 중인 후보를 끊지 않는다.</b> 여기서 하는 일은 <b>더 발행하지 않는다</b>는 판정뿐이다. 이미 큐에 든 후보는 회수할 수
 * 없고(research R2), 계산이 끝나가는 후보를 버리면 그만큼의 계산이 통째로 사라진다. 그래서 한도는 "여기까지만"이고 그 결과가 {@code
 * PARTIAL}이다(FR-009).
 *
 * <p>{@code timeLimitSeconds}는 <b>임시값</b>이다. 실측 전이라 팀이 정하지 않았고(PD-05·PD-06), 응답의 {@code
 * limits.provisional}이 그 사실을 화면에 나른다(FR-005a).
 */
@Component
@ConditionalOnProperty(prefix = "thorp.param-search", name = "enabled", havingValue = "true")
public class TimeLimitGuard {

    private final SearchLimits limits;
    private final Clock clock;

    public TimeLimitGuard(SearchLimits limits, Clock clock) {
        this.limits = limits;
        this.clock = clock;
    }

    /**
     * 실행시간 한도에 닿았는가.
     *
     * <p>시작 시각이 없으면(아직 {@code QUEUED}) 잴 기준이 없으므로 닿지 않은 것으로 본다 — 발행이 시작되지 않았다는 뜻이라 막을 것도 없다.
     */
    public boolean isReached(ParamSearch search) {
        Instant startedAt = search.getStartedAt();
        if (startedAt == null) {
            return false;
        }
        return Duration.between(startedAt, clock.instant()).getSeconds()
                >= limits.timeLimitSeconds();
    }
}
