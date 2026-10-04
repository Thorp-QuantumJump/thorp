package com.ssafy.thorp.backtest.infrastructure.persistence;

/**
 * 목록용 실행 뷰의 native 프로젝션.
 *
 * <p>decimal을 {@code String}으로 받는 이유는 {@link BacktestResultPerformanceRow}와 같다 — {@code ::numeric}
 * 캐스팅은 저장 때 피한 {@code jsonb} 숫자 정규화를 되불러온다.
 *
 * <p>alias는 따옴표로 감싼다 — PostgreSQL이 인용하지 않은 식별자를 소문자로 접는다.
 */
interface BacktestRunViewRow {

    Long getRunId();

    String getBacktestId();

    String getStatus();

    String getNetReturn();

    String getMaxDrawdown();

    String getTradingCost();
}
