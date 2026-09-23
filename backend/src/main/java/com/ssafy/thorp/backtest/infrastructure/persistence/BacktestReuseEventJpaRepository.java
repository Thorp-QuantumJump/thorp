package com.ssafy.thorp.backtest.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** 재사용·합류 기록은 추가만 하고 지우지 않는다. 바꾸는 것은 만료된 비로그인 요청 정리(spec 005 T022)가 요청 참조를 끊는 일 하나다. */
interface BacktestReuseEventJpaRepository extends JpaRepository<BacktestReuseEventJpaEntity, Long> {

    @Modifying(flushAutomatically = true)
    @Query(
            "UPDATE BacktestReuseEventJpaEntity e SET e.requestId = NULL"
                    + " WHERE e.requestId = :requestId")
    int clearRequestId(@Param("requestId") long requestId);
}
