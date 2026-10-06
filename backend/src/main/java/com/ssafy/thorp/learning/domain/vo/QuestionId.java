package com.ssafy.thorp.learning.domain.vo;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 확인 문제 식별자({@code q1} 형식). 서버가 글의 {@code quiz_seq}로 주고 바뀌지 않는다(data-model §2·§6-2, research R21).
 *
 * <p>사용자의 첫 답은 (글, 이 식별자)로 남는다. 같은 문제 판정에서 새 문제가 되면 새 식별자를 받는다.
 */
public record QuestionId(String value) {

    public static final String REGEX = "^q[1-9][0-9]{0,3}$";

    private static final Pattern PATTERN = Pattern.compile(REGEX);

    public QuestionId {
        Objects.requireNonNull(value, "questionId must not be null");
        if (!PATTERN.matcher(value).matches()) {
            throw new IllegalArgumentException("invalid questionId format: " + value);
        }
    }

    /** 카운터 값 {@code n}으로 {@code q{n}}을 만든다. */
    public static QuestionId of(int sequence) {
        return new QuestionId("q" + sequence);
    }

    @Override
    public String toString() {
        return value;
    }
}
