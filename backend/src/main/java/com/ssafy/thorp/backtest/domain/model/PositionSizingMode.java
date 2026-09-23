package com.ssafy.thorp.backtest.domain.model;

/**
 * 포지션 크기 결정 방식(도메인 표현).
 *
 * <p>도메인은 메시지 계약 enum({@code application.port.message.PositionSizingType})을 참조할 수 없어(ADR-0001 계층
 * 규칙) 같은 값을 도메인에도 둔다. 변환은 EngineInput 조립기가 한다.
 */
public enum PositionSizingMode {
    STRATEGY_DEFINED
}
