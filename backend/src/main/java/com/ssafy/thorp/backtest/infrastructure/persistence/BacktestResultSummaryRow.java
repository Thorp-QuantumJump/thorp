package com.ssafy.thorp.backtest.infrastructure.persistence;

import java.time.Instant;

/**
 * 요약 조회의 native 프로젝션. JSON 컬럼은 **문자열로 받아** 구현체가 메시지 ObjectMapper로 파싱한다.
 *
 * <p>{@code BigDecimal}로 받지 않는 이유: 결과 저장이 {@code jsonb}의 숫자 정규화를 피하려고 decimal을 문자열로 넣는다. SQL에서
 * {@code ::numeric}으로 캐스팅하면 그 문제를 되불러오므로 {@code ->>}로 문자열을 꺼내 {@code new BigDecimal(...)}로 복원한다 —
 * scale이 정확히 보존된다.
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

    /** {@code benchmark ->> 'instrumentId'}. 결과에 벤치마크가 없으면 null */
    String getBenchmarkInstrumentId();

    /** {@code benchmark ->> 'totalReturn'}. 문자열 그대로 */
    String getBenchmarkTotalReturn();

    /** {@code costs} JSONB 전체. 5필드. null = 미저장 */
    String getCosts();

    /** {@code liquidation} JSONB 전체. null = 청산을 끈 요청 또는 미저장 */
    String getLiquidation();

    /** {@code analytics} JSONB 전체. 상관 행렬은 종목 수²이지만 50종목이라도 수 KB 다 */
    String getAnalytics();

    /** {@code jsonb_array_length(exceptions)}. 본문은 읽지 않는다. 컬럼이 null 이면 0 */
    Integer getExceptionCount();
}
