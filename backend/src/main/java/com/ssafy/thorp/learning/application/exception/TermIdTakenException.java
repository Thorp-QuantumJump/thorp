package com.ssafy.thorp.learning.application.exception;

import com.ssafy.thorp.common.exception.BusinessException;
import com.ssafy.thorp.common.exception.ErrorCode;
import com.ssafy.thorp.learning.domain.vo.ArticleId;
import com.ssafy.thorp.learning.domain.vo.TermId;
import java.util.Map;

/**
 * 다른 글이 쓰고 있는 {@code termId}를 초안에 넣으려 했다(409 {@code LEARNING_TERM_ID_TAKEN}, learning-admin-api
 * §5).
 *
 * <p>{@code termId}는 만든 뒤 다른 글로 옮길 수 없다(data-model §2). {@code details}에 어느 용어를 어느 글이 쓰는지 싣는다.
 */
public class TermIdTakenException extends BusinessException {

    public TermIdTakenException(TermId termId, ArticleId ownerArticleId) {
        super(
                ErrorCode.LEARNING_TERM_ID_TAKEN,
                ErrorCode.LEARNING_TERM_ID_TAKEN.getMessage(),
                Map.of("termId", termId.value(), "ownerArticleId", ownerArticleId.value()));
    }
}
