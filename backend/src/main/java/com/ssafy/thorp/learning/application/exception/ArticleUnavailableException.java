package com.ssafy.thorp.learning.application.exception;

import com.ssafy.thorp.common.exception.BusinessException;
import com.ssafy.thorp.common.exception.ErrorCode;

/**
 * 볼 수 없는 글(404 {@code LEARNING_ARTICLE_UNAVAILABLE}, learning-public-api §2).
 *
 * <p>없는 글, 초안인 글, 게시 취소된 글, 형식이 틀린 식별자를 <b>가르지 않는다</b>. 가르면 초안 글의 존재가 드러난다. 메시지에도 식별자를 넣지 않고 계약 문장을
 * 그대로 쓴다.
 */
public class ArticleUnavailableException extends BusinessException {

    public ArticleUnavailableException() {
        super(ErrorCode.LEARNING_ARTICLE_UNAVAILABLE);
    }
}
