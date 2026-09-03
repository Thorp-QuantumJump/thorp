package com.thorp.api.backtest.domain.exception;

import com.thorp.api.common.exception.BusinessException;
import com.thorp.api.common.exception.ErrorCode;

public class BacktestNotFoundException extends BusinessException {

    public BacktestNotFoundException(Long backtestId) {
        super(ErrorCode.RESOURCE_NOT_FOUND, "백테스트를 찾을 수 없습니다: id=" + backtestId);
    }
}
