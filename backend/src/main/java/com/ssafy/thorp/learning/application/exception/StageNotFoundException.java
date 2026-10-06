package com.ssafy.thorp.learning.application.exception;

import com.ssafy.thorp.common.exception.BusinessException;
import com.ssafy.thorp.common.exception.ErrorCode;

/** 없는 단계다(404 {@code LEARNING_STAGE_NOT_FOUND}, learning-admin-api §3·§5). 단계는 마이그레이션만 만든다. */
public class StageNotFoundException extends BusinessException {

    public StageNotFoundException() {
        super(ErrorCode.LEARNING_STAGE_NOT_FOUND);
    }
}
