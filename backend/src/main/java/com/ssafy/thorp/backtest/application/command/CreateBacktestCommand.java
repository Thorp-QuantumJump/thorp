package com.ssafy.thorp.backtest.application.command;

import java.time.LocalDate;

/**
 * 백테스트 생성 유스케이스 입력. HTTP 요청 DTO와 분리한다.
 *
 * <p>{@code initialCash}는 KRW 정수이며 요청에서 생략된 경우 컨트롤러가 기본값을 채워 넘긴다.
 */
public record CreateBacktestCommand(
        String strategy, LocalDate startDate, LocalDate endDate, long initialCash) {}
