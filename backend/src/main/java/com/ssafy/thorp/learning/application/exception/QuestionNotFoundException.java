package com.ssafy.thorp.learning.application.exception;

import com.ssafy.thorp.common.exception.BusinessException;
import com.ssafy.thorp.common.exception.ErrorCode;

/**
 * 공개 판에 그 문제가 없거나 그 문제에 그 보기가 없다(404 {@code LEARNING_QUESTION_NOT_FOUND}, learning-me-api §5·§6).
 * 운영자가 문제를 바꿨다. 화면은 글을 다시 불러오게 한다.
 */
public class QuestionNotFoundException extends BusinessException {

    public QuestionNotFoundException() {
        super(ErrorCode.LEARNING_QUESTION_NOT_FOUND);
    }
}
