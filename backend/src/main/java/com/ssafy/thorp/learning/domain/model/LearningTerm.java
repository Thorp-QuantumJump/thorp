package com.ssafy.thorp.learning.domain.model;

import com.ssafy.thorp.learning.domain.vo.TermId;
import java.util.Objects;

/**
 * 용어 설명 하나. 초안 작업본에서도, 판의 내용에서도 같은 모양이다.
 *
 * <p>초안에서는 {@code sentence}가 빈 문자열일 수 있다. 비었는지, 300자를 넘는지는 게시 전 검사({@code TERM_SENTENCE})가 가른다.
 */
public record LearningTerm(TermId termId, String label, String sentence) {

    public static final int LABEL_MAX_LENGTH = 50;
    public static final int SENTENCE_MAX_LENGTH = 300;

    public LearningTerm {
        Objects.requireNonNull(termId, "termId must not be null");
        Objects.requireNonNull(label, "term label must not be null");
        Objects.requireNonNull(sentence, "term sentence must not be null");
    }
}
