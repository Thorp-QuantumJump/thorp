package com.ssafy.thorp.backtest.application.port.message;

/** 비중의 출처(FR-026). 사용자가 직접 입력한 비중과 규칙이 계산한 비중을 구분한다. */
public enum WeightsSource {
    USER,
    COMPUTED
}
