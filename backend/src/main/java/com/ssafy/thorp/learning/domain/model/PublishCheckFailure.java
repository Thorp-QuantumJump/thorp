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

    /**
     * 검사 이름. v1은 data-model §8-1의 아홉, v2는 §8-2의 열일곱을 쓴다. 선언 순서가 두 판의 실행·보고 순서와 맞는다.
     *
     * <p>v1의 {@code TERM_SENTENCE}(설명 문장 하나)와 v2의 {@code TERM_SENTENCES}(이름과 세 문장)는 다른 검사다. v1 이름을
     * 바꾸면 이미 남은 9월 판 거절 응답과 화면의 분기가 어긋난다.
     *
     * <p>{@code FORMAT}·{@code PLACEMENT}·{@code TERM_TRANSFER}·{@code TERM_ID_TAKEN}은 단계 공개와 그 미리
     * 검사만 쓰는 글별 항목이다(learning-admin-api §10). 글마다 게시는 같은 문제를 409로 돌려준다.
     */
    public enum Check {
        REQUIRED_FIELDS,
        KEY_SUMMARY,
        HEADING_COUNT,
        HYPOTHETICAL_PRESENT,
        MISCONCEPTION_PRESENT,
        READING_TIME,
        EXAMPLE_PRESENT,
        EXAMPLE_SECTOR_COUNT,
        EXAMPLE_SECTOR_IN_MAP,
        EXAMPLE_WEIGHT_SUM,
        TERM_BLOCKS_MATCH,
        TERM_SENTENCE,
        TERM_SENTENCES,
        TERM_MENTION_KNOWN,
        QUIZ,
        ELEMENT_FORMAT,
        FORBIDDEN_EXPRESSION,
        RESULT_NUMBER_OUTSIDE_HYPOTHETICAL,
        FORMAT,
        PLACEMENT,
        TERM_TRANSFER,
        TERM_ID_TAKEN
    }

    public PublishCheckFailure {
        Objects.requireNonNull(check, "check must not be null");
        Objects.requireNonNull(location, "location must not be null");
        Objects.requireNonNull(message, "message must not be null");
    }
}
