package com.thorp.api.backtest.presentation.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;

/**
 * 백테스트 Job 생성 요청 (Worker와 공유하는 계약의 백엔드 측 표현).
 *
 * <p>MVP v0.1.1: 알고리즘 1개 / 파라미터 고정 / 기간 5년 고정이므로 strategy 외 파라미터는 서버에서 고정값을 사용한다.
 */
public record BacktestJobRequest(
        @NotBlank String strategy, @NotNull LocalDate startDate, @NotNull LocalDate endDate) {}
