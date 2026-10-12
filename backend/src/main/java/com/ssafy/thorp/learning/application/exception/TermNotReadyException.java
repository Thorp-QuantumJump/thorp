package com.ssafy.thorp.learning.application.exception;

import com.ssafy.thorp.common.exception.BusinessException;
import com.ssafy.thorp.common.exception.ErrorCode;

/**
 * 게시된 설명이 없는 용어(404 {@code LEARNING_TERM_NOT_READY}, learning-public-api §4).
 *
 * <p>초안뿐인 용어, 게시 취소된 글의 용어, 없는 용어, 형식이 틀린 식별자를 가르지 않는다(research R10). 화면은 이 코드일 때만 '설명 준비 중'을 보이고,
 * 그 밖의 실패는 '불러오지 못했다'로 보인다.
 */
public class TermNotReadyException extends BusinessException {

    public TermNotReadyException() {
        super(ErrorCode.LEARNING_TERM_NOT_READY);
    }
}
