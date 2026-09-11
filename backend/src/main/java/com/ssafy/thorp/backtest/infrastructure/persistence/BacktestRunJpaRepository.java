package com.ssafy.thorp.backtest.infrastructure.persistence;

import com.ssafy.thorp.backtest.domain.model.RunStatus;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;

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
}
