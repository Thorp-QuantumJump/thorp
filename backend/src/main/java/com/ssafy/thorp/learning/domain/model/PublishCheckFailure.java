package com.ssafy.thorp.learning.domain.model;

import java.util.Objects;

/**
 * 게시 전 검사에 걸린 항목 하나(learning-admin-api §6).
 *
 * @param matched 걸린 표현이나 숫자. {@code FORBIDDEN_EXPRESSION}·{@code
 *     RESULT_NUMBER_OUTSIDE_HYPOTHETICAL}일 때만 있다
 * @param message 화면이 그대로 보이는 문장. 화면은 문장을 새로 짓지 않는다
 */
public record PublishCheckFailure(
        Check check, CheckLocation location, String matched, String message) {

    /** research R7 표의 아홉 검사. 선언 순서가 실행·보고 순서다. */
    public enum Check {
        REQUIRED_FIELDS,
        EXAMPLE_PRESENT,
        EXAMPLE_SECTOR_COUNT,
        EXAMPLE_SECTOR_IN_MAP,
        EXAMPLE_WEIGHT_SUM,
        TERM_BLOCKS_MATCH,
        TERM_SENTENCE,
        FORBIDDEN_EXPRESSION,
        RESULT_NUMBER_OUTSIDE_HYPOTHETICAL
    }

    public PublishCheckFailure {
        Objects.requireNonNull(check, "check must not be null");
        Objects.requireNonNull(location, "location must not be null");
        Objects.requireNonNull(message, "message must not be null");
    }
}
