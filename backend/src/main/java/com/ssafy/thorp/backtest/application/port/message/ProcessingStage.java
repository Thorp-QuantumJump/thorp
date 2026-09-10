package com.ssafy.thorp.backtest.application.port.message;

/** 진행 단계(Clarify Q4). SAVING은 백엔드가 FINISHED를 저장하는 동안 붙이는 상태라 메시지에는 오지 않는다. */
public enum ProcessingStage {
    PREPARING_DATA,
    COMPUTING
}
