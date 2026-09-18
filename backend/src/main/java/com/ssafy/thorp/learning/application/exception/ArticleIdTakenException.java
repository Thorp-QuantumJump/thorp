package com.ssafy.thorp.learning.application.exception;

import com.ssafy.thorp.common.exception.BusinessException;
import com.ssafy.thorp.common.exception.ErrorCode;

/**
 * 이미 쓰인 {@code articleId}로 글을 만들려 했다(409 {@code LEARNING_ARTICLE_ID_TAKEN}, learning-admin-api §3).
 */
public class ArticleIdTakenException extends BusinessException {

    public ArticleIdTakenException() {
        super(ErrorCode.LEARNING_ARTICLE_ID_TAKEN);
    }
}
