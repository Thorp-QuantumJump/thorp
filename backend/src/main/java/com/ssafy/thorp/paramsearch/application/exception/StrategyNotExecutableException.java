package com.ssafy.thorp.paramsearch.application.exception;

import com.ssafy.thorp.common.exception.BusinessException;
import com.ssafy.thorp.common.exception.ErrorCode;
import java.util.List;

/**
 * 등록돼 있으나 004가 실행을 지원하지 않는 전략이다(FR-026). 422다.
 *
 * <p>조합 생성 전에 던진다. 그대로 발행하면 후보 전량이 실패로 끝나 "실행 불가"와 "낮은 수익"의 구분이 흐려지고 실행 자원도 버린다(원칙 VI, research
 * R8).
 */
public class StrategyNotExecutableException extends BusinessException {

    public StrategyNotExecutableException(List<String> strategyIds) {
        super(
                ErrorCode.STRATEGY_NOT_EXECUTABLE,
                "아직 실행을 지원하지 않는 전략입니다: " + String.join(", ", strategyIds));
    }
}
