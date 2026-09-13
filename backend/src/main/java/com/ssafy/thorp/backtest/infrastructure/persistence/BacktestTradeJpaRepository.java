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
     * <p>{@code visibleBefore}(null = 제한 없음)는 먼저 취소한 요청의 경계다(R-04). <b>경계 전에 청산된 거래만</b> 보인다.
     * 저장된 거래 행은 실행 <b>종료</b> 시점의 값이다 — 가로지르는 거래는 청산가·실현손익이 경계 밖이고, 끝까지 OPEN 인 거래도 분할·부분
     * 매도로 수량·진입가·수수료가 경계 뒤에 바뀐다. 경계 시점 상태를 되살리려면 그때 값을 따로 저장해야 하는데 백엔드는 결과를 재구성하지
     * 않으므로(원칙 1) 빼는 쪽을 택했다. 그래서 취소 요청의 거래 목록은 전체 진입 내역이 아니며, 경계 시점 보유는 {@code /positions}가 준다.
     * {@code exitTime IS NULL} 은 비교가 거짓이라 자연히 빠진다.
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
                    OR t.exitTime < :visibleBefore)
             ORDER BY t.entryTime ASC, t.id ASC
            """)
    Page<BacktestTradeJpaEntity> findPage(
            @Param("jobId") Long jobId,
            @Param("instrumentId") String instrumentId,
            @Param("status") TradeStatus status,
            @Param("visibleBefore") Instant visibleBefore,
            Pageable pageable);
}
