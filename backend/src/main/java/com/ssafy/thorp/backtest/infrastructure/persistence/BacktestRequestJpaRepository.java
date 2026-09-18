package com.ssafy.thorp.backtest.infrastructure.persistence;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
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
     *
     * <p><b>가리키는 실행이 아직 끝나지 않은 요청은 고르지 않는다</b>(spec 005 T093). 만료는 생성 시각부터 세므로 대기열이 유효 시간보다 밀리면 대기
     * 중인 요청도 만료된다. 그 행을 지우면 비로그인 대기 몫({@code countQueuedSingleAnonymous}, 요청 행과 조인해 센다)에서 빠져 한도가
     * 새고, 볼 사람이 없는 실행이 워커를 끝까지 차지한다. 실행이 끝난 뒤 다음 주기에 지운다. 조건을 질의에 두는 이유: 배치를 뽑은 뒤 거르면 끝나지 않은 행이 배치를
     * 채워 뒤의 만료 행을 영영 못 지운다.
     */
    @Query(
            """
            SELECT r FROM BacktestRequestJpaEntity r
             WHERE r.requesterId IS NULL
               AND r.publicTokenExpiresAt <= :now
               AND EXISTS (
                   SELECT 1 FROM BacktestRunJpaEntity run
                    WHERE run.id = r.runId
                      AND run.status IN (
                          com.ssafy.thorp.backtest.domain.model.RunStatus.COMPLETED,
                          com.ssafy.thorp.backtest.domain.model.RunStatus.FAILED,
                          com.ssafy.thorp.backtest.domain.model.RunStatus.CANCELLED))
             ORDER BY r.publicTokenExpiresAt ASC, r.id ASC
            """)
    List<BacktestRequestJpaEntity> findExpiredAnonymous(
            @Param("now") Instant now, Pageable pageable);

    /** 일괄 UPDATE 라 영속성 컨텍스트를 거치지 않는다. 호출 뒤 같은 트랜잭션에서 재시도 요청을 다시 읽지 않는다. */
    @Modifying(flushAutomatically = true)
    @Query(
            "UPDATE BacktestRequestJpaEntity r SET r.retryOfRequestId = NULL"
                    + " WHERE r.retryOfRequestId = :requestId")
    int clearRetryOf(@Param("requestId") long requestId);
}
