package com.ssafy.thorp.backtest.application.port.message;

/**
 * 수수료 모델.
 *
 * <p>{@code ZERO}는 수수료·세금·제비용을 모두 0으로 계산하며 {@code broker}를 쓰지 않는다. 임시 우회값이 아니라 "거래비용을 제외한 순수 전략
 * 성능"을 뜻하는 정식 모델이다. {@code BROKER}는 증권사 요율표대로 계산하고 {@code broker}가 필수다. 근거는 ADR-0004.
 */
public enum CommissionModel {
    BROKER,
    ZERO
}
