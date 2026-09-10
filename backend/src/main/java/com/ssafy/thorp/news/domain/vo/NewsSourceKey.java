package com.ssafy.thorp.news.domain.vo;

import java.util.Objects;
import java.util.Optional;

/**
 * 수집 상태와 점유를 식별하는 키(spec FR-022).
 *
 * <p>키 형식을 여기 한곳에서 정한다. 호출부가 임의 문자열을 만들면 마지막 갱신 시각 계산이 매체와 뉴스원을 이어 붙이지 못해, 수집이 성공했는데도 화면이 '아직 수집되지
 * 않음'으로 보이게 된다.
 *
 * <p>RSS 키는 {@code rss:{publisherKey}}다. 마지막 갱신 시각 질의는 이 접두를 리터럴로 복사하지 않고 {@link #RSS_PREFIX}를 인자로
 * 받으므로, 형식을 바꿔도 질의만 조용히 어긋나는 일이 없다.
 */
public final class NewsSourceKey {

    /** RSS 뉴스원 키 접두. 마지막 갱신 시각 질의가 같은 값을 쓴다. */
    public static final String RSS_PREFIX = "rss:";

    /** GDELT 뉴스원 키 접두. 매체에 매이지 않는다. */
    public static final String GDELT_PREFIX = "gdelt:";

    private NewsSourceKey() {}

    /** 매체의 RSS 수집 키를 만든다. */
    public static String rss(String publisherKey) {
        Objects.requireNonNull(publisherKey, "publisherKey must not be null");
        if (publisherKey.isBlank()) {
            throw new IllegalArgumentException("publisherKey는 비어 있을 수 없다");
        }
        return RSS_PREFIX + publisherKey;
    }

    /** GDELT 수집 키를 만든다. */
    public static String gdelt(String name) {
        Objects.requireNonNull(name, "name must not be null");
        if (name.isBlank()) {
            throw new IllegalArgumentException("name은 비어 있을 수 없다");
        }
        return GDELT_PREFIX + name;
    }

    /** RSS 키에서 매체 키를 꺼낸다. RSS 키가 아니면 빈 값이다. */
    public static Optional<String> publisherKeyOf(String sourceKey) {
        if (sourceKey == null || !sourceKey.startsWith(RSS_PREFIX)) {
            return Optional.empty();
        }
        String publisherKey = sourceKey.substring(RSS_PREFIX.length());
        return publisherKey.isBlank() ? Optional.empty() : Optional.of(publisherKey);
    }
}
