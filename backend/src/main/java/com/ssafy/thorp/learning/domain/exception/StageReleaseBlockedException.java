package com.ssafy.thorp.learning.domain.exception;

import com.ssafy.thorp.common.exception.BusinessException;
import com.ssafy.thorp.common.exception.ErrorCode;
import com.ssafy.thorp.learning.domain.model.StageRelease.Blocker;

/**
 * 글 검사와 별개로 단계 공개가 막혔다(409, learning-admin-api §11 "그 밖의 실패"). 아무것도 쓰지 않았다. 막는 것은 단계 공개의 도메인
 * 규칙({@link com.ssafy.thorp.learning.domain.model.StageRelease#blockers})이 정한다.
 *
 * <table>
 *   <caption>막는 것과 오류 코드</caption>
 *   <tr><th>막는 것</th><th>코드</th></tr>
 *   <tr><td>{@code ALREADY_RELEASED}</td><td>{@code LEARNING_STAGE_ALREADY_RELEASED}</td></tr>
 *   <tr><td>{@code ORDER}</td><td>{@code LEARNING_STAGE_ORDER}</td></tr>
 *   <tr><td>{@code EMPTY}</td><td>{@code LEARNING_STAGE_EMPTY}</td></tr>
 * </table>
 */
public class StageReleaseBlockedException extends BusinessException {

    public StageReleaseBlockedException(Blocker blocker) {
        super(code(blocker));
    }

    private static ErrorCode code(Blocker blocker) {
        return switch (blocker) {
            case ALREADY_RELEASED -> ErrorCode.LEARNING_STAGE_ALREADY_RELEASED;
            case ORDER -> ErrorCode.LEARNING_STAGE_ORDER;
            case EMPTY -> ErrorCode.LEARNING_STAGE_EMPTY;
        };
    }
}
