package com.ssafy.thorp.assistant.application.service;

import com.ssafy.thorp.assistant.application.port.AssistantSettings;
import com.ssafy.thorp.common.exception.BusinessException;
import com.ssafy.thorp.common.exception.ErrorCode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * 남용 방지 한도(015 FR-013, 017 FR-011): IP별 분당, 화면 몫 분당(결과 화면은 토큰별, 진단 화면은 종류별 IP), 하루 전체. 넘으면 429
 * {@code ASSISTANT_LIMIT}과 다시 가능한 시점이다. IP 분당과 하루 전체는 모든 화면 종류가 함께 센다.
 *
 * <p>고정 창 카운터다. 분 창은 벽시계의 분 경계, 하루 창은 UTC 자정 경계이고 {@code retryAfterSeconds}는 그 창이 끝날 때까지다.
 */
// ponytail: 인스턴스 메모리라 다중 인스턴스에서는 한도가 인스턴스 수만큼 늘어난다(보안 경계가 아니다, spec Edge Cases). 그때 공유 저장소로 옮긴다.
@Component
public class AssistantRateLimiter {
    private static final Duration MINUTE = Duration.ofMinutes(1);
    private static final Duration DAY = Duration.ofDays(1);
    private static final int PRUNE_THRESHOLD = 5_000;

    private final AssistantSettings settings;
    private final Clock clock;
    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();

    public AssistantRateLimiter(AssistantSettings settings, Clock clock) {
        this.settings = settings;
        this.clock = clock;
    }

    /**
     * 순서: IP → 화면 몫 → 하루. 앞 단계에서 거절되면 뒷 단계는 세지 않는다.
     *
     * @param scopeKey 화면 몫 키. 결과 화면은 {@code token:{publicToken}}, 진단 화면은 {@code page:{kind}:{ip}}
     * @param reason 넘었을 때 {@code details.reason}. {@code PER_TOKEN} 또는 {@code PER_PAGE}
     */
    public void admit(String clientIp, String scopeKey, int scopeLimit, String reason) {
        Instant now = clock.instant();
        prune(now);
        count("ip:" + clientIp, MINUTE, settings.perIpPerMinute(), now, "PER_IP");
        count(scopeKey, MINUTE, scopeLimit, now, reason);
        count("day", DAY, settings.perDay(), now, "PER_DAY");
    }

    private void count(String key, Duration span, int limit, Instant now, String reason) {
        long spanSeconds = span.toSeconds();
        long bucket = now.getEpochSecond() / spanSeconds;
        long expiresAt = (bucket + 1) * spanSeconds;
        Window window =
                windows.compute(
                        key,
                        (ignored, current) ->
                                current == null || current.expiresAt() != expiresAt
                                        ? new Window(expiresAt, 1)
                                        : new Window(expiresAt, current.count() + 1));
        if (window.count() > limit) {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("reason", reason);
            details.put("limit", limit);
            details.put("retryAfterSeconds", Math.max(1, expiresAt - now.getEpochSecond()));
            throw new BusinessException(
                    ErrorCode.ASSISTANT_LIMIT, ErrorCode.ASSISTANT_LIMIT.getMessage(), details);
        }
    }

    private void prune(Instant now) {
        if (windows.size() > PRUNE_THRESHOLD) {
            long nowSeconds = now.getEpochSecond();
            windows.entrySet().removeIf(entry -> entry.getValue().expiresAt() <= nowSeconds);
        }
    }

    private record Window(long expiresAt, int count) {}
}
