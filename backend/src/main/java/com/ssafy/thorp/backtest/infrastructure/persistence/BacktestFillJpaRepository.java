package com.ssafy.thorp.backtest.infrastructure.persistence;

import java.time.Instant;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface BacktestFillJpaRepository extends JpaRepository<BacktestFillJpaEntity, Long> {

    /**
     * 구간 안의 체결을 체결 순서대로 돌려준다. {@code from}은 포함, {@code to}는 포함하지 않는다(null = 그쪽 제한 없음). 인덱스 {@code
     * (run_id, fill_time, id)}를 그대로 탄다.
     */
    @Query(
            """
            SELECT f FROM BacktestFillJpaEntity f
             WHERE f.job.id = :runId
               AND (CAST(:from AS java.time.Instant) IS NULL OR f.fillTime >= :from)
               AND (CAST(:to AS java.time.Instant) IS NULL OR f.fillTime < :to)
             ORDER BY f.fillTime ASC, f.id ASC
            """)
    List<BacktestFillJpaEntity> findInPeriod(
            @Param("runId") Long runId, @Param("from") Instant from, @Param("to") Instant to);
}
