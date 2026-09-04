package com.ssafy.thorp.strategy.domain.exception;

import com.ssafy.thorp.common.exception.BusinessException;
import com.ssafy.thorp.common.exception.ErrorCode;

/** 카탈로그에 없는 전략을 조회했을 때. 404로 응답한다. */
public class StrategyNotFoundException extends BusinessException {

    public StrategyNotFoundException(String strategyId) {
        super(ErrorCode.RESOURCE_NOT_FOUND, "전략을 찾을 수 없습니다: strategyId=" + strategyId);
    }
}
