package com.ssafy.thorp.learning.domain.exception;

import com.ssafy.thorp.common.exception.BusinessException;
import com.ssafy.thorp.common.exception.ErrorCode;

/**
 * 이전 형식(V1)으로 다룰 수 없는 글이다(409 {@code LEARNING_FORMAT_OUTDATED}, research R16). V1 초안은 공개 전 단계에서 이전
 * 판 고치기로만 게시하고, 이전 판(LEGACY)이 없는 글은 V1로 되돌릴 수 없다. 새 글을 이전 판으로 게시하는 길을 막는다.
 */
public class FormatOutdatedException extends BusinessException {

    public FormatOutdatedException() {
        super(ErrorCode.LEARNING_FORMAT_OUTDATED);
    }
}
