package com.ssafy.thorp.learning.domain.vo;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 용어 설명 식별자({@code termId}). 만든 뒤 바뀌지 않고 다른 글로 옮길 수 없다(data-model §2).
 *
 * <p>002·003·005 화면이 이 값으로 도움말을 요청한다. 표시 이름으로 찾으면 운영자가 이름을 고치는 순간 연결이 끊긴다(FR-008).
 */
public record TermId(String value) {

    public static final int MIN_LENGTH = 2;
    public static final int MAX_LENGTH = 64;

    private static final Pattern PATTERN = Pattern.compile(ArticleId.REGEX);

    public TermId {
        Objects.requireNonNull(value, "termId must not be null");
        if (value.length() < MIN_LENGTH
                || value.length() > MAX_LENGTH
                || !PATTERN.matcher(value).matches()) {
            throw new IllegalArgumentException("invalid termId format: " + value);
        }
    }

    @Override
    public String toString() {
        return value;
    }
}
