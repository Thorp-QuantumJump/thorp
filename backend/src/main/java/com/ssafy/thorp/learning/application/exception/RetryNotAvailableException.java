package com.ssafy.thorp.learning.application.exception;

import com.ssafy.thorp.common.exception.BusinessException;
import com.ssafy.thorp.common.exception.ErrorCode;

/**
 * 다시 풀기를 할 수 없는 글이다(409 {@code LEARNING_RETRY_NOT_AVAILABLE}, learning-me-api §6). 아직 답하지 않은 문제가
 * 있거나, 첫 답에서 하나 이상 맞혔거나, 이미 익힘이다.
 */
public class RetryNotAvailableException extends BusinessException {

    public RetryNotAvailableException() {
        super(ErrorCode.LEARNING_RETRY_NOT_AVAILABLE);
    }
}
