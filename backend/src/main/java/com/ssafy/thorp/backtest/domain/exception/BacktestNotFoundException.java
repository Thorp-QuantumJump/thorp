package com.ssafy.thorp.backtest.domain.exception;

import com.ssafy.thorp.common.exception.BusinessException;
import com.ssafy.thorp.common.exception.ErrorCode;

public class BacktestNotFoundException extends BusinessException {

    public BacktestNotFoundException(String backtestId) {
        super(ErrorCode.RESOURCE_NOT_FOUND, "백테스트를 찾을 수 없습니다: backtestId=" + backtestId);
    }
}
