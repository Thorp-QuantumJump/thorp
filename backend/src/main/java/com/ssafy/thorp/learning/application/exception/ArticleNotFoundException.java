package com.ssafy.thorp.learning.application.exception;

import com.ssafy.thorp.common.exception.BusinessException;
import com.ssafy.thorp.common.exception.ErrorCode;

/**
 * 관리할 글이 없다(404 {@code LEARNING_ARTICLE_NOT_FOUND}, learning-admin-api §4~§7).
 *
 * <p>공개 조회의 {@code LEARNING_ARTICLE_UNAVAILABLE}과 코드를 나눈다. 관리 계정에게는 초안 글이 보이므로 "없는 글"과 "공개되지 않은 글"을
 * 숨길 이유가 없다. 형식이 틀린 식별자도 여기다.
 */
public class ArticleNotFoundException extends BusinessException {

    public ArticleNotFoundException() {
        super(ErrorCode.LEARNING_ARTICLE_NOT_FOUND);
    }
}
