package com.ssafy.thorp.learning.domain.exception;

import com.ssafy.thorp.common.exception.BusinessException;
import com.ssafy.thorp.common.exception.ErrorCode;

/**
 * 공개 전 단계의 V2 글을 하나씩 게시하려 했다(409 {@code LEARNING_STAGE_NOT_RELEASED}, research R16). 공개 전 단계의 새 기준
 * 글은 '단계 공개'로 함께 게시한다. 일부만 게시된 중간 상태를 만들지 않는다(FR-031, SC-017).
 */
public class StageNotReleasedException extends BusinessException {

    public StageNotReleasedException() {
        super(ErrorCode.LEARNING_STAGE_NOT_RELEASED);
    }
}
