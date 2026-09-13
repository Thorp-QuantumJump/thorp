package com.ssafy.thorp.news.application.result;

import java.util.List;
import java.util.Objects;

/**
 * 외부 뉴스원 한 번의 호출 결과. 순수 record다.
 *
 * <p>{@code resultCapReached}는 뉴스원이 결과 상한 때문에 요청 구간의 기사를 다 주지 못했다는 뜻이다. 호출부는 구간을 절반씩 나누어 다시 조회하고,
 * 최소 구간에서도 상한이면 그 구간을 INCOMPLETE로 남긴다. 부분 수집을 완전 수집으로 기록하지 않는다(spec FR-013).
 *
 * <p>{@code notModified}는 조건부 요청에 뉴스원이 {@code 304}로 답했다는 뜻이다. 성공으로 기록하되 수집 위치와 기사 데이터는 바꾸지 않는다.
 *
 * @param items 가져온 기사 메타데이터.
 * @param notModified 변경 없음 응답 여부.
 * @param resultCapReached 결과 상한 도달 여부.
 * @param etag 응답의 ETag. 다음 조건부 요청에 쓴다.
 * @param lastModified 응답의 Last-Modified.
 * @param channelHints RSS 채널이 알려 준 호출 간격 힌트. 없으면 {@link RssChannelHints#NONE}.
 */
public record NewsFetchResult(
        List<CollectedNewsItem> items,
        boolean notModified,
        boolean resultCapReached,
        String etag,
        String lastModified,
        RssChannelHints channelHints) {

    public NewsFetchResult {
        channelHints = channelHints == null ? RssChannelHints.NONE : channelHints;
        items = items == null ? List.of() : List.copyOf(items);
        if (notModified && !items.isEmpty()) {
            throw new IllegalArgumentException("변경 없음 응답에는 기사가 없어야 한다");
        }
    }

    /** 변경 없음 응답. 수집 위치와 기사 데이터를 그대로 두게 한다. */
    public static NewsFetchResult notModified(String etag, String lastModified) {
        return new NewsFetchResult(
                List.of(), true, false, etag, lastModified, RssChannelHints.NONE);
    }

    /** 변경 없음 응답이지만 채널 힌트는 살아 있는 경우. 힌트는 본문과 무관하게 유지된다. */
    public static NewsFetchResult notModified(
            String etag, String lastModified, RssChannelHints hints) {
        return new NewsFetchResult(List.of(), true, false, etag, lastModified, hints);
    }

    /** 구간을 모두 받은 정상 응답. */
    public static NewsFetchResult complete(
            List<CollectedNewsItem> items, String etag, String lastModified) {
        return complete(items, etag, lastModified, RssChannelHints.NONE);
    }

    /** 채널 힌트까지 담은 정상 응답. */
    public static NewsFetchResult complete(
            List<CollectedNewsItem> items,
            String etag,
            String lastModified,
            RssChannelHints hints) {
        return new NewsFetchResult(items, false, false, etag, lastModified, hints);
    }

    /** 결과 상한에 걸려 구간을 다 받지 못한 응답. */
    public static NewsFetchResult capped(List<CollectedNewsItem> items) {
        return new NewsFetchResult(
                Objects.requireNonNull(items), false, true, null, null, RssChannelHints.NONE);
    }
}
