package com.ssafy.thorp.learning.domain.vo;

import java.util.Objects;
import java.util.regex.Pattern;

/** 확인 문제 보기 식별자({@code c1} 형식). 문제 식별자와 같은 카운터로 서버가 주고 바뀌지 않는다(data-model §2·§6-2). */
public record ChoiceId(String value) {

    public static final String REGEX = "^c[1-9][0-9]{0,3}$";

    private static final Pattern PATTERN = Pattern.compile(REGEX);

    public ChoiceId {
        Objects.requireNonNull(value, "choiceId must not be null");
        if (!PATTERN.matcher(value).matches()) {
            throw new IllegalArgumentException("invalid choiceId format: " + value);
        }
    }

    /** 카운터 값 {@code n}으로 {@code c{n}}을 만든다. */
    public static ChoiceId of(int sequence) {
        return new ChoiceId("c" + sequence);
    }

    @Override
    public String toString() {
        return value;
    }
}
