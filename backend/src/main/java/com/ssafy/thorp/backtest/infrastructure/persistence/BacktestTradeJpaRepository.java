package com.ssafy.thorp.backtest.infrastructure.persistence;

import com.ssafy.thorp.backtest.application.port.message.TradeStatus;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface BacktestTradeJpaRepository extends JpaRepository<BacktestTradeJpaEntity, Long> {

    /** 조회 API의 기본 정렬과 같다. 인덱스 {@code (run_id, entry_time, id)}를 그대로 탄다. */
    List<BacktestTradeJpaEntity> findByJobIdOrderByEntryTimeAscIdAsc(Long jobId);

    /**
     * 필터 조합이 네 가지라 파생 쿼리 네 개도, {@code Specification}도 만들지 않는다. nullable 파라미터 두 개가 가장 짧다.
     *
     * <p>정렬을 쿼리에 박아 {@code Pageable}의 {@code Sort}를 무시한다 — 계약이 고정했고 인덱스 {@code (run_id, entry_time,
     * id)}를 그대로 탄다.
     */
    @Query(
            """
            SELECT t FROM BacktestTradeJpaEntity t
             WHERE t.job.id = :jobId
               AND (:instrumentId IS NULL OR t.instrumentId = :instrumentId)
               AND (:status IS NULL OR t.status = :status)
             ORDER BY t.entryTime ASC, t.id ASC
            """)
    Page<BacktestTradeJpaEntity> findPage(
            @Param("jobId") Long jobId,
            @Param("instrumentId") String instrumentId,
            @Param("status") TradeStatus status,
            Pageable pageable);
}
