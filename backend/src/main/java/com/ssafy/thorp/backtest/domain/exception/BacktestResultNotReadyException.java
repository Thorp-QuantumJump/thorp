package com.ssafy.thorp.backtest.domain.exception;

import com.ssafy.thorp.backtest.domain.model.BacktestStatus;
import com.ssafy.thorp.common.exception.BusinessException;
import com.ssafy.thorp.common.exception.ErrorCode;

/** COMPLETED가 아니거나 결과 행이 아직 없는 백테스트의 결과를 조회하려 할 때 던진다. 409로 응답된다. */
public class BacktestResultNotReadyException extends BusinessException {

    public BacktestResultNotReadyException(Long backtestId, BacktestStatus status) {
        super(
                ErrorCode.RESULT_NOT_READY,
                "백테스트 결과가 아직 준비되지 않았습니다: id=" + backtestId + ", status=" + status);
    }
}
