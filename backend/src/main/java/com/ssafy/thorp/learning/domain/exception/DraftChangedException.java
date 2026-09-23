package com.ssafy.thorp.learning.domain.exception;

import com.ssafy.thorp.common.exception.BusinessException;
import com.ssafy.thorp.common.exception.ErrorCode;
import java.util.Map;

/**
 * 읽은 뒤 초안이 바뀌었다(409 {@code LEARNING_DRAFT_CHANGED}, research R12).
 *
 * <p>{@code details.currentDraftVersion}에 서버 값을 싣는다. 화면은 입력을 남긴 채 새로 불러오기를 안내하고, 자동으로 병합하거나 다시 보내지
 * 않는다.
 */
public class DraftChangedException extends BusinessException {

    public DraftChangedException(int currentDraftVersion) {
        super(
                ErrorCode.LEARNING_DRAFT_CHANGED,
                ErrorCode.LEARNING_DRAFT_CHANGED.getMessage(),
                Map.of("currentDraftVersion", currentDraftVersion));
    }
}
