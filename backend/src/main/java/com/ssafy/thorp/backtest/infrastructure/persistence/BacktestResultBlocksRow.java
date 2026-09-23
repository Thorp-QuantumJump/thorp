package com.ssafy.thorp.backtest.infrastructure.persistence;

/**
 * 비중·예외 블록 조회의 native 프로젝션. JSON 컬럼은 문자열로 받아 구현체가 파싱한다.
 *
 * <p>alias를 따옴표로 감싸는 이유는 {@link BacktestResultSummaryRow} javadoc과 같다.
 */
interface BacktestResultBlocksRow {

    /** null = 엔진이 비중 기록을 채우지 않았다 */
    String getWeightsSeries();

    /** {@code USER} / {@code COMPUTED}. null = 미기록 */
    String getWeightsSource();

    /** null = 예외 기록 없음(미저장) */
    String getExceptions();
}
