package com.ssafy.thorp.paramsearch.application.exception;

import com.ssafy.thorp.common.exception.BusinessException;
import com.ssafy.thorp.common.exception.ErrorCode;
import java.util.List;

/**
 * 목표 비중이 사용자에게서 오는 전략이라 자동 탐색이 실행할 수 없다. 422다.
 *
 * <p><b>{@link StrategyNotExecutableException}과 다르다.</b> 004는 이 전략을 실행할 수 있고 단건 분석은 그대로 된다. 자동 탐색이
 * 못 하는 것은 목표 비중을 만들어 내는 일이다 — 그 값은 사용자가 정하고 탐색 요청에는 담기지 않는다. 두 사유를 하나로 뭉치면 사용자가 "기다리면 되는 일"로 잘못
 * 읽는다.
 *
 * <p>조합 생성 전에 던진다. 그대로 발행하면 후보 전량이 접수에서 목표 비중 누락으로 거절돼 실행 자원을 버린다(원칙 VI).
 */
public class StrategyNotSearchableException extends BusinessException {

    public StrategyNotSearchableException(List<String> strategyIds) {
        super(
                ErrorCode.STRATEGY_NOT_SEARCHABLE,
                "목표 비중을 직접 정하는 전략은 자동 탐색으로 비교할 수 없습니다: " + String.join(", ", strategyIds));
    }
}
