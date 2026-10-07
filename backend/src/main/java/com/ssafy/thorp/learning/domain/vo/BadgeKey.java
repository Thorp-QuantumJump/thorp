package com.ssafy.thorp.learning.domain.vo;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 배지 식별자({@code badgeKey}). 코드 {@code BadgeCatalog}가 정하고 바뀌지 않는다(data-model §2, research R30).
 *
 * <p>형식은 {@link ArticleId}와 같고 길이만 3~70자다. 가장 긴 키가 {@code stage-{stageKey}}(6자 + 최대 64자)이고, {@code
 * learning_badge_award.badge_key}가 같은 길이다.
 */
public record BadgeKey(String value) {

    public static final int MIN_LENGTH = 3;
    public static final int MAX_LENGTH = 70;

    private static final Pattern PATTERN = Pattern.compile(ArticleId.REGEX);

    public BadgeKey {
        Objects.requireNonNull(value, "badgeKey must not be null");
        if (value.length() < MIN_LENGTH
                || value.length() > MAX_LENGTH
                || !PATTERN.matcher(value).matches()) {
            throw new IllegalArgumentException("invalid badgeKey format: " + value);
        }
    }

    @Override
    public String toString() {
        return value;
    }
}
