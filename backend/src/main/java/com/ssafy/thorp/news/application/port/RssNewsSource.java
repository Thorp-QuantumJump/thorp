package com.ssafy.thorp.news.application.port;

import com.ssafy.thorp.news.domain.vo.NewsSourceKey;
import java.util.Objects;

/**
 * 한 매체의 RSS 채널 수집 대상. 순수 record이며 Spring·JPA·Jackson에 의존하지 않는다.
 *
 * <p>수집 키는 {@link NewsSourceKey#rss(String)}로 매체 키에서 만든다. 임의 문자열을 받지 않는 이유는 마지막 갱신 시각 질의가 이 키로 매체와
 * 뉴스원을 잇기 때문이다. 형식이 어긋나면 수집이 성공해도 화면이 '아직 수집되지 않음'으로 보인다(spec FR-014).
 *
 * <p>{@code etag}·{@code lastModified}는 조건부 요청에 쓴다. 뉴스원이 {@code 304 Not Modified}를 주면 성공으로 기록해 마지막
 * 성공 시각만 갱신하고 수집 위치와 기사 데이터는 그대로 둔다(spec FR-013).
 *
 * @param publisherId 매체 식별자.
 * @param publisherKey 매체 키. 수집 키를 이 값에서 만든다.
 * @param feedUrl 공식 채널 주소. 서버 전용이며 로그·지표·응답에 남기지 않는다(spec FR-021).
 * @param etag 직전 응답의 ETag. 없으면 null.
 * @param lastModified 직전 응답의 Last-Modified. 없으면 null.
 * @param channelTopicKeys 이 채널이 다루는 주제. 기사가 분류 값을 주지 않는 매체를 위한 보완 값이다.
 */
public record RssNewsSource(
        Long publisherId,
        String publisherKey,
        String feedUrl,
        String etag,
        String lastModified,
        java.util.List<String> channelTopicKeys) {

    public RssNewsSource {
        Objects.requireNonNull(publisherId, "publisherId must not be null");
        Objects.requireNonNull(publisherKey, "publisherKey must not be null");
        Objects.requireNonNull(feedUrl, "feedUrl must not be null");
        if (publisherKey.isBlank() || feedUrl.isBlank()) {
            throw new IllegalArgumentException("publisherKey와 feedUrl은 비어 있을 수 없다");
        }
        channelTopicKeys =
                channelTopicKeys == null
                        ? java.util.List.of()
                        : java.util.List.copyOf(channelTopicKeys);
    }

    /** 채널 주제를 선언하지 않은 뉴스원. 기사가 분류 값을 주는 매체는 이쪽이다. */
    public RssNewsSource(
            Long publisherId,
            String publisherKey,
            String feedUrl,
            String etag,
            String lastModified) {
        this(publisherId, publisherKey, feedUrl, etag, lastModified, java.util.List.of());
    }

    /** 수집 상태와 점유를 식별하는 키. 매체 키에서 결정론적으로 만든다. */
    public String sourceKey() {
        return NewsSourceKey.rss(publisherKey);
    }

    /** 조건부 요청에 쓸 값이 있는지 여부. */
    public boolean hasConditionalRequestState() {
        return etag != null || lastModified != null;
    }
}
