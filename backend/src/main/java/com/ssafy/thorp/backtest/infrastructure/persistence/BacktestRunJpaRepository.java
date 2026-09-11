package com.ssafy.thorp.backtest.infrastructure.persistence;

import com.ssafy.thorp.backtest.domain.model.RunStatus;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface BacktestRunJpaRepository extends JpaRepository<BacktestRunJpaEntity, Long> {

    Optional<BacktestRunJpaEntity> findByBacktestId(String backtestId);

    /**
     * 같은 동일 버전 키를 가진 <b>살아 있는</b> 실행. 부분 유니크 인덱스 {@code uk_backtest_run_dedup_active} 와 같은 상태 집합을
     * 본다 — 실패·취소한 실행은 재사용 대상이 아니라서 같은 키로 새 실행을 만들 수 있다.
     */
    Optional<BacktestRunJpaEntity> findByDedupKeyAndStatusIn(
            String dedupKey, Collection<RunStatus> statuses);

    /** 커밋됐지만 발행 확인이 없는 대기 실행. 오래된 것부터 본다. */
    List<BacktestRunJpaEntity>
            findByStatusAndPublishedAtIsNullAndQueuedAtLessThanOrderByQueuedAtAsc(
                    RunStatus status, Instant queuedBefore, Limit limit);

    /**
     * 발행 시도 횟수를 <b>DB에서</b> 올린다(R-18).
     *
     * <p>읽어서 +1 해 쓰면 첫 발행과 재발행이 겹쳤을 때 증가 하나를 잃는다. 잃는 방향이 위험하다 — 시도 횟수가 실제 메시지 수보다 작아지면 DLQ 종결
     * 판정({@code dlqCount >= publishCount})이 아직 살아 있는 실행을 실패로 끊는다.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update BacktestRunJpaEntity r set r.publishCount = r.publishCount + 1 where r.id = :id")
    int incrementPublishCount(@Param("id") Long id);

    /** 발행 confirm 시각. 이 컬럼만 건드려 상태 전이와 부딪히지 않는다. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update BacktestRunJpaEntity r set r.publishedAt = :at where r.id = :id")
    int markPublished(@Param("id") Long id, @Param("at") Instant at);
}
