package com.ssafy.thorp.learning.domain.vo;

import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 글 식별자({@code articleId}). 만든 뒤 바뀌지 않으며 글 주소와 002 예시 진입의 {@code topicId}로 쓰인다(data-model §2).
 *
 * <p>형식은 {@code learning_article.article_key}의 CHECK 제약과 같다. 두 쪽이 다른 집합을 허용하면 도메인이 만든 값을 DB가 거절하거나
 * 그 반대가 된다.
 */
public record ArticleId(String value) {

    public static final int MIN_LENGTH = 3;
    public static final int MAX_LENGTH = 64;

    /** 소문자·숫자 조각을 하이픈 하나로 잇는다. 앞뒤 하이픈과 연속 하이픈은 허용하지 않는다. */
    public static final String REGEX = "^[a-z0-9]+(-[a-z0-9]+)*$";

    private static final Pattern PATTERN = Pattern.compile(REGEX);

    public ArticleId {
        Objects.requireNonNull(value, "articleId must not be null");
        if (value.length() < MIN_LENGTH
                || value.length() > MAX_LENGTH
                || !PATTERN.matcher(value).matches()) {
            throw new IllegalArgumentException("invalid articleId format: " + value);
        }
    }

    /**
     * 경로 변수처럼 밖에서 온 문자열을 읽는다. 형식이 틀렸거나 null이면 비어 있다. 형식 오류를 어떤 응답으로 바꿀지(볼 수 없는 글 404, 관리할 글 없음
     * 404)는 호출자가 정한다.
     */
    public static Optional<ArticleId> parse(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(new ArticleId(raw));
        } catch (IllegalArgumentException invalid) {
            return Optional.empty();
        }
    }

    @Override
    public String toString() {
        return value;
    }
}
