package com.ssafy.thorp.learning.application.exception;

import com.ssafy.thorp.common.exception.BusinessException;
import com.ssafy.thorp.common.exception.ErrorCode;

/**
 * 기록하지 않는 글이거나 나의 학습이 꺼져 있다(409 {@code LEARNING_NOT_RECORDABLE}, learning-me-api §0·§1).
 *
 * <p>기록하는 글은 공개된 글({@code CURRENT} 판이 게시 중인 글)뿐이다. 이전 판·첫 단계 공개 전 글({@code LEGACY} 판)은 읽을 수 있지만
 * 기록하지 않는다(FR-031). 첫 단계를 공개하기 전의 {@code GET /learning/me}도 이 코드다.
 */
public class NotRecordableException extends BusinessException {

    public NotRecordableException() {
        super(ErrorCode.LEARNING_NOT_RECORDABLE);
    }
}
