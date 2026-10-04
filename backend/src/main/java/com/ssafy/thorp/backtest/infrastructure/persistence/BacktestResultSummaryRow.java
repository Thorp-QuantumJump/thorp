package com.ssafy.thorp.backtest.infrastructure.persistence;

import java.time.Instant;

/**
 * 요약 조회의 native 프로젝션. JSON 컬럼은 **문자열로 받아** 구현체가 메시지 ObjectMapper로 파싱한다.
 *
 * <p>getter 이름과 SQL alias가 맞아야 한다. **alias는 따옴표로 감싼다** — PostgreSQL은 인용하지 않은 식별자를 소문자로 접어 {@code
 * errorcode}가 되고 여기 매핑이 깨진다.
 */
interface BacktestResultSummaryRow {

    String getErrorCode();

    String getErrorMessage();

    Instant getStartedAt();

    Instant getFinishedAt();

    /** {@code performance} JSONB 전체. 10필드라 통째로 읽어도 작다. */
    String getPerformance();

    /** {@code execution} JSONB 전체. 5필드. */
    String getExecution();

    /** {@code costs} JSONB 전체. 5필드. null = 미저장 */
    String getCosts();

    /** {@code liquidation} JSONB 전체. null = 청산을 끈 요청 또는 미저장 */
    String getLiquidation();

    /** {@code analytics} JSONB 전체. 상관 행렬은 종목 수²이지만 50종목이라도 수 KB 다 */
    String getAnalytics();

    /** {@code jsonb_array_length(exceptions)}. 본문은 읽지 않는다. 컬럼이 null 이면 0 */
    Integer getExceptionCount();

    /** {@code versions ->> 'worker'}. 결과를 낸 워커가 보고한 자기 버전. v2 이전 결과·실패 결과는 null */
    String getWorkerVersion();

    /** {@code versions -> 'dependencies'}. 결과를 계산한 워커 환경의 numpy·scipy 버전. T028 이전 결과·실패 결과는 null */
    String getDependencies();
}
