package com.ssafy.thorp.backtest.application.command;

import java.time.LocalDate;

/** 백테스트 생성 유스케이스 입력. HTTP 요청 DTO와 분리한다. */
public record CreateBacktestCommand(String strategy, LocalDate startDate, LocalDate endDate) {}
