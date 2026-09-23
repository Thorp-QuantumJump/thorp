package com.ssafy.thorp.backtest.domain.exception;

import com.ssafy.thorp.common.exception.BusinessException;
import com.ssafy.thorp.common.exception.ErrorCode;

/**
 * 하위 리소스(시계열·거래·보유)를 조회했지만 실행이 실패로 끝났다. {@code FAILED}에서 던진다.
 *
 * <p>실패한 실행에는 결과 행이 있지만 시계열과 거래가 비어 있다(오류 정보만 저장한다). 그것을 그대로 200으로 내려주면 "거래가 한 건도 없는 정상 실행"과 구별되지
 * 않으므로 409로 막는다.
 *
 * <p>{@link BacktestResultNotReadyException}과 달리 **재시도해도 결과가 달라지지 않는다.** 실패 원인은 {@code GET
 * /backtests/{backtestId}}의 {@code error}에 있다.
 */
public class BacktestFailedException extends BusinessException {

    public BacktestFailedException(String backtestId) {
        super(ErrorCode.BACKTEST_FAILED, "백테스트가 실패해 결과가 없습니다: backtestId=" + backtestId);
    }
}
