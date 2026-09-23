package com.ssafy.thorp.paramsearch.application.exception;

import com.ssafy.thorp.common.exception.BusinessException;
import com.ssafy.thorp.common.exception.ErrorCode;
import com.ssafy.thorp.paramsearch.domain.model.ParamSearch;
import com.ssafy.thorp.paramsearch.domain.model.StopReason;

/**
 * 접수 시점 거부(contracts/param-search-api.md §2). 상한 초과·유효 조합 0건·전략 거부가 여기 들어간다.
 *
 * <p><b>묶음은 이미 저장됐다.</b> 사용자의 선택이 남긴 결과이므로 무엇을 왜 실행하지 않았는지가 재현 기록의 일부다(FR-020). 그래서 예외를 던지면서도 트랜잭션을
 * 되돌리면 안 된다 — {@code rollbackOn}에서 빠지도록 호출 지점에서 표시한다.
 *
 * <p>거부 사유마다 HTTP 상태가 다르다. 화면이 "범위를 좁혀라"와 "전략을 바꿔라"를 구분해 안내해야 하기 때문이다(FR-005b·FR-026).
 */
public class ParamSearchRejectedException extends BusinessException {

    private final transient ParamSearch rejected;

    public ParamSearchRejectedException(
            StopReason stopReason, ParamSearch rejected, String message) {
        super(errorCodeOf(stopReason), message);
        this.rejected = rejected;
    }

    /** 저장된 거부 묶음. 응답에 {@code searchId}를 담아 사용자가 기록을 되짚을 수 있게 한다. */
    public ParamSearch getRejected() {
        return rejected;
    }

    private static ErrorCode errorCodeOf(StopReason stopReason) {
        return switch (stopReason) {
            case CANDIDATE_LIMIT -> ErrorCode.CANDIDATE_LIMIT_EXCEEDED;
            case NO_VALID_CANDIDATE -> ErrorCode.NO_VALID_COMBINATION;
            case STRATEGY_NOT_REGISTERED -> ErrorCode.STRATEGY_NOT_REGISTERED;
            case STRATEGY_NOT_EXECUTABLE -> ErrorCode.STRATEGY_NOT_EXECUTABLE;
            case STRATEGY_NOT_SEARCHABLE -> ErrorCode.STRATEGY_NOT_SEARCHABLE;
            // 취소·시간·실패 한도는 접수 거부가 아니라 실행 중 중단이다. 여기로 오면 호출 지점이 틀렸다.
            case CANCELLED, TIME_LIMIT, FAILURE_LIMIT ->
                    throw new IllegalArgumentException("not an intake rejection: " + stopReason);
        };
    }
}
