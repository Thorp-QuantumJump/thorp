package com.ssafy.thorp.learning.domain.vo;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 단계 식별자({@code stageKey}). 마이그레이션만 만들고 바뀌지 않는다(data-model §2). 배지와 경험치 장부도 이 값으로 단계를 가리킨다.
 *
 * <p>형식은 {@link ArticleId}와 같고 {@code learning_stage.stage_key}의 CHECK 제약과도 같다.
 */
public record StageKey(String value) {

    public static final int MIN_LENGTH = 3;
    public static final int MAX_LENGTH = 64;

    private static final Pattern PATTERN = Pattern.compile(ArticleId.REGEX);

    public StageKey {
        Objects.requireNonNull(value, "stageKey must not be null");
        if (value.length() < MIN_LENGTH
                || value.length() > MAX_LENGTH
                || !PATTERN.matcher(value).matches()) {
            throw new IllegalArgumentException("invalid stageKey format: " + value);
        }
    }

    @Override
    public String toString() {
        return value;
    }
}
