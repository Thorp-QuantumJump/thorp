package com.ssafy.thorp.backtest.infrastructure.persistence;

/**
 * {@code backtest_result.equity_curve} JSON 배열의 원소. Hibernate가 Jackson으로 직렬화·역직렬화한다.
 *
 * <p>키 이름은 Worker와 공유하는 저장 계약({@code
 * specs/003-backtest-result-analysis/contracts/backtest-result-table.md})과 일치해야 한다. {@code date}는
 * ISO-8601 문자열({@code "2019-01-02"})로 두어 날짜 모듈 등록 여부에 의존하지 않는다. 도메인 {@code EquityPoint}와의 변환은
 * {@link BacktestResultMapper}가 한다.
 */
public record EquityPointJson(String date, long equity, double cumulativeReturn, double drawdown) {}
