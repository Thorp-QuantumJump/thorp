package com.ssafy.thorp.backtest.application.port.message;

/** 엔진 실행 결과. 최종 상태만 온다(비최종 상태는 계약 오류). CANCELLED는 부분 결과와 함께 온다. */
public enum RunStatus {
    COMPLETED,
    FAILED,
    CANCELLED
}
