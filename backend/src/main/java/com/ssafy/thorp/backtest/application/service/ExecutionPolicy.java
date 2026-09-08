package com.ssafy.thorp.backtest.application.service;

/**
 * 사용자가 고르지 않고 서버가 고정하는 실행 정책.
 *
 * <p>계약 기준: {@code docs/specs/backtest-messaging/README.md} §4.1. 엔진 계약이 바뀌면 이 클래스와 README, 픽스처
 * {@code engine-input.json}만 고치면 된다.
 */
public final class ExecutionPolicy {

    /** 전략 카탈로그가 생기기 전까지 쓰는 고정 버전. 결과 재현과 설정 조회에 필요하다. */
    public static final String STRATEGY_VERSION = "1.0.0";

    /** 결과 재현을 위한 기본 시드. */
    public static final int DEFAULT_RANDOM_SEED = 42;

    private ExecutionPolicy() {}
}
