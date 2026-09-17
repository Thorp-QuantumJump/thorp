package com.ssafy.thorp.backtest.infrastructure.persistence;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
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

    Optional<BacktestRequestJpaEntity> findByPublicToken(String publicToken);

    /**
     * 부분 인덱스 {@code idx_backtest_request_public_expiry}를 탄다 — 조건에 {@code publicTokenExpiresAt IS
     * NOT NULL}이 함축되고 로그인 요청은 인덱스에 없다. 개수는 {@code Pageable}로 자른다.
     */
    @Query(
            """
            SELECT r FROM BacktestRequestJpaEntity r
             WHERE r.requesterId IS NULL
               AND r.publicTokenExpiresAt <= :now
             ORDER BY r.publicTokenExpiresAt ASC, r.id ASC
            """)
    List<BacktestRequestJpaEntity> findExpiredAnonymous(
            @Param("now") Instant now, Pageable pageable);
}
