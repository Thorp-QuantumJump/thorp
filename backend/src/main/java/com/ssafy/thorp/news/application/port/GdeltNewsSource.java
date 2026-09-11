package com.ssafy.thorp.news.application.port;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * GDELT 보완 수집 대상. 순수 record다.
 *
 * <p>기간과 주제를 제한해 조회하며 장기 원천 전체를 적재하지 않는다(spec FR-013). 요청 구간은 수집 위치가 가리키는 지점부터이며, 결과 상한에 걸리면 호출부가
 * 구간을 절반씩 나누어 다시 조회한다.
 *
 * @param sourceKey 수집 상태와 점유를 식별하는 키. {@link
 *     com.ssafy.thorp.news.domain.vo.NewsSourceKey#gdelt(String)}로 만든다.
 * @param windowStart 조회 구간 시작.
 * @param windowEnd 조회 구간 끝.
 * @param topicKeys 조회를 좁히는 등록된 주제 키.
 */
public record GdeltNewsSource(
        String sourceKey, Instant windowStart, Instant windowEnd, List<String> topicKeys) {

    /** 구간을 더 쪼갤 수 없는 최소 길이. 이 크기에서도 상한이면 그 구간을 INCOMPLETE로 남긴다(spec FR-013). */
    public static final java.time.Duration MIN_WINDOW = java.time.Duration.ofMinutes(15);

    public GdeltNewsSource {
        Objects.requireNonNull(sourceKey, "sourceKey must not be null");
        Objects.requireNonNull(windowStart, "windowStart must not be null");
        Objects.requireNonNull(windowEnd, "windowEnd must not be null");
        if (!windowStart.isBefore(windowEnd)) {
            throw new IllegalArgumentException("windowStart는 windowEnd보다 앞서야 한다");
        }
        topicKeys = topicKeys == null ? List.of() : List.copyOf(topicKeys);
    }

    /** 이 구간을 더 쪼갤 수 있는지. 최소 길이보다 길어야 한다. */
    public boolean isDivisible() {
        return java.time.Duration.between(windowStart, windowEnd).compareTo(MIN_WINDOW) > 0;
    }

    /** 구간을 절반으로 나눈 앞쪽. 결과 상한에 걸렸을 때 쓴다. */
    public GdeltNewsSource firstHalf() {
        return new GdeltNewsSource(sourceKey, windowStart, midpoint(), topicKeys);
    }

    /** 구간을 절반으로 나눈 뒤쪽. */
    public GdeltNewsSource secondHalf() {
        return new GdeltNewsSource(sourceKey, midpoint(), windowEnd, topicKeys);
    }

    private Instant midpoint() {
        long half = java.time.Duration.between(windowStart, windowEnd).dividedBy(2).toSeconds();
        return windowStart.plusSeconds(half);
    }
}
