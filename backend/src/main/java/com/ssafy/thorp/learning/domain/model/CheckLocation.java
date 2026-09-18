package com.ssafy.thorp.learning.domain.model;

import com.ssafy.thorp.learning.domain.vo.TermId;
import java.util.Objects;

/**
 * 게시 전 검사에 걸린 위치(learning-admin-api §6).
 *
 * @param index {@code BLOCK}일 때 {@code blocks} 순번(0부터). 본문 전체를 가리키면(문단이 하나도 없음) null이다
 * @param termId {@code TERM}일 때만 있다
 */
public record CheckLocation(Part part, Integer index, TermId termId) {

    /** 위치의 종류. 응답에는 소문자 이름으로 나간다. */
    public enum Part {
        TITLE,
        QUESTION,
        EXAMPLE,
        BLOCK,
        TERM
    }

    public CheckLocation {
        Objects.requireNonNull(part, "part must not be null");
        if (index != null && part != Part.BLOCK) {
            throw new IllegalArgumentException("index is only for block locations: " + part);
        }
        if ((termId != null) != (part == Part.TERM)) {
            throw new IllegalArgumentException("termId must be present exactly for term locations");
        }
    }

    public static CheckLocation title() {
        return new CheckLocation(Part.TITLE, null, null);
    }

    public static CheckLocation question() {
        return new CheckLocation(Part.QUESTION, null, null);
    }

    public static CheckLocation example() {
        return new CheckLocation(Part.EXAMPLE, null, null);
    }

    public static CheckLocation block(int index) {
        return new CheckLocation(Part.BLOCK, index, null);
    }

    /** 특정 블록이 아니라 본문 전체. */
    public static CheckLocation body() {
        return new CheckLocation(Part.BLOCK, null, null);
    }

    public static CheckLocation term(TermId termId) {
        return new CheckLocation(Part.TERM, null, termId);
    }
}
