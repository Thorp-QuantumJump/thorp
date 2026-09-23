package com.ssafy.thorp.paramsearch.application.exception;

import com.ssafy.thorp.common.exception.BusinessException;
import com.ssafy.thorp.common.exception.ErrorCode;
import java.util.List;

/**
 * 요청 전략이 003 등록표의 활성 전략에 없다(FR-026). 422다.
 *
 * <p>조합 생성 전에 던진다. 실행 미지원({@link StrategyNotExecutableException})과 구분하는 이유는 사용자가 할 수 있는 일이 다르기 때문이다
 * — 등록되지 않은 전략은 등록을 기다려야 하고, 등록됐지만 실행이 안 되는 전략은 004의 지원을 기다려야 한다.
 */
public class StrategyNotRegisteredException extends BusinessException {

    public StrategyNotRegisteredException(List<String> strategyIds) {
        super(
                ErrorCode.STRATEGY_NOT_REGISTERED,
                "등록표에 없는 전략입니다: " + String.join(", ", strategyIds));
    }
}
