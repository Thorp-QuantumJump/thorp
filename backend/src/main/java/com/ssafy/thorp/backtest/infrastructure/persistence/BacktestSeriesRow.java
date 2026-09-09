package com.ssafy.thorp.backtest.infrastructure.persistence;

/**
 * 시계열 조회의 native 프로젝션. 두 JSON 컬럼을 문자열로 받아 구현체가 파싱한다.
 *
 * <p>alias를 따옴표로 감싸는 이유는 {@link BacktestResultSummaryRow} javadoc과 같다.
 */
interface BacktestSeriesRow {

    String getPortfolioSeries();

    /** null = 결과에 벤치마크가 없음 */
    String getBenchmark();
}
