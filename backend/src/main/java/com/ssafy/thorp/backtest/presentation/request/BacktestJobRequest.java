package com.ssafy.thorp.backtest.presentation.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.LocalDate;

/**
 * 백테스트 Job 생성 요청 (Worker와 공유하는 계약의 백엔드 측 표현).
 *
 * <p>MVP v0.1.1: 알고리즘 1개 / 파라미터 고정 / 기간 5년 고정이므로 strategy 외 파라미터는 서버에서 고정값을 사용한다.
 *
 * <p>{@code initialCash}(KRW 정수)는 선택이다. 생략하면 {@link #DEFAULT_INITIAL_CASH}(10,000,000)를 적용한다.
 */
public record BacktestJobRequest(
        @NotBlank String strategy,
        @NotNull LocalDate startDate,
        @NotNull LocalDate endDate,
        @Positive Long initialCash) {

    public static final long DEFAULT_INITIAL_CASH = 10_000_000L;

    /** 요청에 initialCash가 없으면 기본값을 돌려준다. */
    public long initialCashOrDefault() {
        return initialCash == null ? DEFAULT_INITIAL_CASH : initialCash;
    }
}
