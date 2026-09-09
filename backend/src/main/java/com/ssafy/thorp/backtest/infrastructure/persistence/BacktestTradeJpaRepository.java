package com.ssafy.thorp.backtest.infrastructure.persistence;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

interface BacktestTradeJpaRepository extends JpaRepository<BacktestTradeJpaEntity, Long> {

    /** 조회 API의 기본 정렬과 같다. 인덱스 {@code (backtest_job_id, entry_time, id)}를 그대로 탄다. */
    List<BacktestTradeJpaEntity> findByJobIdOrderByEntryTimeAscIdAsc(Long jobId);
}
