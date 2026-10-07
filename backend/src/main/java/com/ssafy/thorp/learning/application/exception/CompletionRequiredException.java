package com.ssafy.thorp.learning.application.exception;

import com.ssafy.thorp.common.exception.BusinessException;
import com.ssafy.thorp.common.exception.ErrorCode;

/** 완료하지 않은 글의 확인 문제에 답했다(409 {@code LEARNING_COMPLETION_REQUIRED}, learning-me-api §5, FR-022). */
public class CompletionRequiredException extends BusinessException {

    public CompletionRequiredException() {
        super(ErrorCode.LEARNING_COMPLETION_REQUIRED);
    }
}
