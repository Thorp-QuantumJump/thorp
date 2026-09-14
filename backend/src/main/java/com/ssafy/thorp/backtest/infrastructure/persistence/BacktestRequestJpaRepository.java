package com.ssafy.thorp.backtest.infrastructure.persistence;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface BacktestRequestJpaRepository extends JpaRepository<BacktestRequestJpaEntity, Long> {

    Optional<BacktestRequestJpaEntity> findByBacktestId(String backtestId);

    /** 취소 경로: 요청 행을 잠근 채 읽는다(R-04). 잠금 순서는 언제나 <b>요청 → 실행</b>이다. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from BacktestRequestJpaEntity r where r.backtestId = :backtestId")
    Optional<BacktestRequestJpaEntity> lockByBacktestId(@Param("backtestId") String backtestId);

    List<BacktestRequestJpaEntity> findByRunId(Long runId);

    long countByRunIdAndCancelledAtIsNull(Long runId);
}
