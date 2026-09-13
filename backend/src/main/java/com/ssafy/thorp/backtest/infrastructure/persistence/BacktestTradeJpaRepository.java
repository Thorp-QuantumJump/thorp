package com.ssafy.thorp.backtest.infrastructure.persistence;

import com.ssafy.thorp.backtest.application.port.message.TradeStatus;
import java.time.Instant;
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
     * <p>{@code visibleBefore}(null = 제한 없음)는 먼저 취소한 요청의 경계다(R-04). <b>진입과 청산이 모두 경계 전인 거래만</b> 보인다
     * — 경계를 가로지르는 거래는 경계 시점엔 열려 있었지만 저장된 행은 청산가·실현손익까지 담고 있어, 그대로 주면 스냅샷 밖 결과가 새고 청산 정보를 지워 OPEN 으로
     * 꾸미면 수수료(양쪽 합)를 되돌릴 수 없어 합성 행이 된다. 백엔드는 결과를 재구성하지 않으므로 빼는 쪽을 택했다. 실행 종료까지 열려 있던 거래({@code
     * exitTime IS NULL})는 경계 시점에도 열려 있었으니 그대로 보인다.
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
               AND (CAST(:visibleBefore AS java.time.Instant) IS NULL
                    OR (t.entryTime < :visibleBefore
                        AND (t.exitTime IS NULL OR t.exitTime < :visibleBefore)))
             ORDER BY t.entryTime ASC, t.id ASC
            """)
    Page<BacktestTradeJpaEntity> findPage(
            @Param("jobId") Long jobId,
            @Param("instrumentId") String instrumentId,
            @Param("status") TradeStatus status,
            @Param("visibleBefore") Instant visibleBefore,
            Pageable pageable);
}
