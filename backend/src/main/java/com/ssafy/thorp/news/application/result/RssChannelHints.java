package com.ssafy.thorp.news.application.result;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Set;

/**
 * RSS 채널이 알려 주는 호출 간격 힌트(plan "수집과 재시도").
 *
 * <p>매체가 "이만큼 쉬었다 오라", "이 시간대와 요일에는 오지 말라"고 밝힌 것이다. 지키지 않으면 매체 쪽 부하를 늘리고 차단당할 수 있다.
 *
 * <p>{@code skipHours}의 시각은 RSS 2.0 규약대로 GMT 기준이다. 서버 시간대로 해석하면 매체가 요청한 시간대를 비껴간다.
 *
 * @param ttl 최소 호출 간격. 없으면 null.
 * @param skipHours 호출하지 않을 시각(0~23, GMT).
 * @param skipDays 호출하지 않을 요일 이름(영문, 대소문자 무시).
 */
public record RssChannelHints(Duration ttl, Set<Integer> skipHours, Set<String> skipDays) {

    /** 힌트를 하나도 주지 않은 채널. */
    public static final RssChannelHints NONE = new RssChannelHints(null, Set.of(), Set.of());

    /** 허용 시각을 찾을 때 앞으로 훑어볼 최대 범위. 이 안에 없으면 힌트를 무시한다. */
    private static final int MAX_LOOKAHEAD_HOURS = 48;

    public RssChannelHints {
        skipHours = Set.copyOf(skipHours);
        skipDays =
                skipDays.stream()
                        .map(day -> day.toLowerCase(java.util.Locale.ROOT))
                        .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    /**
     * 다음에 부를 시각을 정한다.
     *
     * <p>기본 간격과 {@code ttl} 중 <b>긴 쪽</b>을 쓴다. 매체가 더 자주 와도 된다고 해도 우리 수집 주기보다 빠르게 돌지는 않는다.
     *
     * <p>그렇게 구한 시각이 매체가 피하라고 한 시간대나 요일이면 한 시간씩 밀어 허용되는 첫 시각을 찾는다. 48시간을 훑어도 없으면 힌트가 사실상 모든 시각을 막은
     * 것이므로 기본 간격을 그대로 쓴다.
     */
    public Instant nextAttemptFrom(Instant from, Duration defaultInterval) {
        Duration interval =
                ttl != null && ttl.compareTo(defaultInterval) > 0 ? ttl : defaultInterval;
        Instant candidate = from.plus(interval);

        if (skipHours.isEmpty() && skipDays.isEmpty()) {
            return candidate;
        }
        for (int i = 0; i < MAX_LOOKAHEAD_HOURS; i++) {
            if (isAllowed(candidate)) {
                return candidate;
            }
            candidate = candidate.plus(Duration.ofHours(1));
        }
        return from.plus(interval);
    }

    private boolean isAllowed(Instant at) {
        ZonedDateTime gmt = at.atZone(ZoneOffset.UTC);
        if (skipHours.contains(gmt.getHour())) {
            return false;
        }
        String day = gmt.getDayOfWeek().name().toLowerCase(java.util.Locale.ROOT);
        return !skipDays.contains(day);
    }
}
