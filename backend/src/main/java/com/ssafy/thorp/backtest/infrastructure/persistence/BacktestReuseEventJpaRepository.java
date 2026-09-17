package com.ssafy.thorp.backtest.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** 재사용·합류 기록은 추가만 한다. 삭제는 만료된 비로그인 요청 정리(spec 005 T022) 하나다. */
interface BacktestReuseEventJpaRepository extends JpaRepository<BacktestReuseEventJpaEntity, Long> {

    @Modifying(flushAutomatically = true)
    @Query("DELETE FROM BacktestReuseEventJpaEntity e WHERE e.requestId = :requestId")
    int deleteByRequestId(@Param("requestId") long requestId);
}
