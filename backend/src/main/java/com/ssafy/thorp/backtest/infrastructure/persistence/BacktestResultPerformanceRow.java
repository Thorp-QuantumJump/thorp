package com.ssafy.thorp.backtest.infrastructure.persistence;

/**
 * 핵심 숫자 셋만 읽는 native 프로젝션.
 *
 * <p>decimal을 {@code String}으로 받는다. 저장이 {@code jsonb}의 숫자 정규화를 피하려고 문자열로 넣었으므로 {@code ->>}로 꺼내
 * {@code new BigDecimal(...)}로 복원해야 scale이 보존된다. {@code ::numeric} 캐스팅은 그 문제를 되불러온다.
 *
 * <p>alias는 따옴표로 감싼다 — PostgreSQL이 인용하지 않은 식별자를 소문자로 접어 매핑이 깨진다.
 */
interface BacktestResultPerformanceRow {

    Long getRunId();

    String getNetReturn();

    String getMaxDrawdown();

    String getTradingCost();
}
