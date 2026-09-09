package com.ssafy.thorp.backtest.domain.exception;

import com.ssafy.thorp.backtest.domain.model.BacktestStatus;
import com.ssafy.thorp.common.exception.BusinessException;
import com.ssafy.thorp.common.exception.ErrorCode;

/**
 * 하위 리소스(시계열·거래·보유)를 조회했지만 실행이 아직 끝나지 않았다. {@code QUEUED}·{@code RUNNING}에서 던진다.
 *
 * <p>빈 배열로 응답하지 않는 이유: 아직 없는 결과를 {@code []}로 주면 "돌았고 자산 곡선이 비어 있었다"는 거짓이 된다.
 *
 * <p>{@link BacktestFailedException}과 나누는 이유: 프론트가 재시도 여부를 코드로 갈라야 한다. 이 예외는 "기다리면 온다"이고 저쪽은 "기다려도
 * 안 온다"다.
 */
public class BacktestResultNotReadyException extends BusinessException {

    public BacktestResultNotReadyException(String backtestId, BacktestStatus status) {
        super(
                ErrorCode.RESULT_NOT_READY,
                "백테스트 결과가 아직 없습니다: backtestId=" + backtestId + ", status=" + status);
    }
}
