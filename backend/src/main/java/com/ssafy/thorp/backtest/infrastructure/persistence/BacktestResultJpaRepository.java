package com.ssafy.thorp.backtest.infrastructure.persistence;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface BacktestResultJpaRepository extends JpaRepository<BacktestResultJpaEntity, Long> {

    /**
     * 요약 조회. **select 목록에 {@code portfolio_series}와 {@code benchmark} 전체가 없는 것이 이 쿼리의 존재 이유다** —
     * 엔티티를 읽으면 5년치 수백 KB가 함께 온다. 이 값을 쓰는 엔드포인트는 프론트가 폴링한다.
     *
     * <p>벤치마크는 스칼라 두 개만 {@code ->>}로 꺼낸다. {@code ::numeric} 캐스팅을 하지 않는 이유는 {@link
     * BacktestResultSummaryRow} javadoc에 있다.
     */
    @Query(
            value =
                    """
                    SELECT error_code        AS "errorCode",
                           error_message     AS "errorMessage",
                           started_at        AS "startedAt",
                           finished_at       AS "finishedAt",
                           performance::text AS "performance",
                           execution::text   AS "execution",
                           benchmark ->> 'instrumentId' AS "benchmarkInstrumentId",
                           benchmark ->> 'totalReturn'  AS "benchmarkTotalReturn",
                           costs::text       AS "costs",
                           liquidation::text AS "liquidation",
                           analytics::text   AS "analytics",
                           COALESCE(jsonb_array_length(exceptions), 0) AS "exceptionCount",
                           versions ->> 'worker' AS "workerVersion",
                           (versions -> 'dependencies')::text AS "dependencies"
                      FROM backtest_result
                     WHERE run_id = :jobId
                    """,
            nativeQuery = true)
    Optional<BacktestResultSummaryRow> findSummaryRow(@Param("jobId") Long jobId);

    /**
     * 실행 여러 건의 핵심 숫자 셋. {@code performance} JSONB에서 세 값만 꺼낸다.
     *
     * <p>자동 탐색의 후보 목록·선정 계산이 쓰는 경로다. 후보가 묶음당 수백 건이라 요약 쿼리를 건마다 돌리면 왕복과 역직렬화가 그만큼 늘어난다.
     */
    @Query(
            value =
                    """
                    SELECT run_id AS "runId",
                           performance ->> 'netTotalReturn'   AS "netReturn",
                           performance ->> 'maxDrawdown'      AS "maxDrawdown",
                           performance ->> 'totalTradingCost' AS "tradingCost"
                      FROM backtest_result
                     WHERE run_id IN (:runIds)
                    """,
            nativeQuery = true)
    List<BacktestResultPerformanceRow> findPerformanceRows(
            @Param("runIds") Collection<Long> runIds);

    /** 비중 기록·출처·예외 기록. {@code /weights}·{@code /exceptions}용이며 시계열은 읽지 않는다. */
    @Query(
            value =
                    """
                    SELECT weights_series::text AS "weightsSeries",
                           weights_source       AS "weightsSource",
                           exceptions::text     AS "exceptions"
                      FROM backtest_result
                     WHERE run_id = :jobId
                    """,
            nativeQuery = true)
    Optional<BacktestResultBlocksRow> findBlocksRow(@Param("jobId") Long jobId);

    /** 시계열 + 벤치마크. 성과·집계·오류 컬럼은 읽지 않는다. */
    @Query(
            value =
                    """
                    SELECT portfolio_series::text AS "portfolioSeries",
                           benchmark::text        AS "benchmark"
                      FROM backtest_result
                     WHERE run_id = :jobId
                    """,
            nativeQuery = true)
    Optional<BacktestSeriesRow> findSeriesRow(@Param("jobId") Long jobId);

    /** 시계열만. 보유 구성 조회는 벤치마크가 필요 없다. */
    @Query(
            value =
                    """
                    SELECT portfolio_series::text
                      FROM backtest_result
                     WHERE run_id = :jobId
                    """,
            nativeQuery = true)
    Optional<String> findPortfolioSeriesJson(@Param("jobId") Long jobId);
}
