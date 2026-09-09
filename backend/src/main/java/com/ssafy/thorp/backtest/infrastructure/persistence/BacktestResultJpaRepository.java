package com.ssafy.thorp.backtest.infrastructure.persistence;

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
                           benchmark ->> 'totalReturn'  AS "benchmarkTotalReturn"
                      FROM backtest_result
                     WHERE backtest_job_id = :jobId
                    """,
            nativeQuery = true)
    Optional<BacktestResultSummaryRow> findSummaryRow(@Param("jobId") Long jobId);

    /** 시계열 + 벤치마크. 성과·집계·오류 컬럼은 읽지 않는다. */
    @Query(
            value =
                    """
                    SELECT portfolio_series::text AS "portfolioSeries",
                           benchmark::text        AS "benchmark"
                      FROM backtest_result
                     WHERE backtest_job_id = :jobId
                    """,
            nativeQuery = true)
    Optional<BacktestSeriesRow> findSeriesRow(@Param("jobId") Long jobId);

    /** 시계열만. 보유 구성 조회는 벤치마크가 필요 없다. */
    @Query(
            value =
                    """
                    SELECT portfolio_series::text
                      FROM backtest_result
                     WHERE backtest_job_id = :jobId
                    """,
            nativeQuery = true)
    Optional<String> findPortfolioSeriesJson(@Param("jobId") Long jobId);
}
