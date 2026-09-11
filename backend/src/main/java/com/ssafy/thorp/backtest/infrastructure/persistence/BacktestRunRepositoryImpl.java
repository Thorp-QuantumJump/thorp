package com.ssafy.thorp.backtest.infrastructure.persistence;

import com.ssafy.thorp.backtest.application.repository.BacktestRunRepository;
import com.ssafy.thorp.backtest.domain.model.BacktestRun;
import com.ssafy.thorp.backtest.domain.model.RunProgress;
import com.ssafy.thorp.backtest.domain.model.RunState;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/**
 * 저장 시 외부 식별자를 부여한다.
 *
 * <p>{@code save()}는 신규 엔티티에 대해 {@code persist()}를 호출하고, SEQUENCE라 그 시점에 PK가 나온다. 그 PK로 {@code
 * backtest_id}를 INSERT 전에 채우므로 INSERT는 한 번이고 컬럼을 NOT NULL로 둘 수 있다. 날짜만 여기서 정하고 PK와의 결합은 엔티티의
 * {@code @PrePersist}가 한다.
 */
@Repository
public class BacktestRunRepositoryImpl implements BacktestRunRepository {

    private final BacktestRunJpaRepository jpaRepository;
    private final Clock clock;

    public BacktestRunRepositoryImpl(BacktestRunJpaRepository jpaRepository, Clock clock) {
        this.jpaRepository = jpaRepository;
        this.clock = clock;
    }

    @Override
    public BacktestRun save(BacktestRun run) {
        if (run.getId() != null) {
            throw new IllegalArgumentException("save is for new runs only: id=" + run.getId());
        }
        BacktestRunJpaEntity entity = BacktestRunMapper.toEntity(run);
        entity.assignBacktestIdOn(LocalDate.now(clock));
        return BacktestRunMapper.toDomain(jpaRepository.save(entity));
    }

    /**
     * 관리 엔티티를 읽어 상태만 바꾼다. 도메인 객체를 새 엔티티로 merge하면 {@code createdAt}(updatable=false)이 null인 사본이 관리
     * 상태를 덮어 쓰므로 그 방식은 피한다. {@code save()}는 트랜잭션 밖에서 불려도 UPDATE가 나가도록 한 번 명시한다.
     */
    @Override
    public BacktestRun update(BacktestRun run) {
        if (run.getId() == null) {
            throw new IllegalArgumentException("update is for persisted runs only: id is null");
        }
        BacktestRunJpaEntity entity =
                jpaRepository
                        .findById(run.getId())
                        .orElseThrow(
                                () ->
                                        new IllegalArgumentException(
                                                "run not found: id=" + run.getId()));
        RunState state = run.getState();
        RunProgress progress = state.progress();
        entity.applyState(
                state.status(),
                state.attempt(),
                state.currentExecutionId(),
                state.partial(),
                progress == null ? null : progress.stage(),
                progress == null ? null : progress.lastTradingDate(),
                progress == null ? null : progress.completedTradingDays(),
                progress == null ? null : progress.totalTradingDays(),
                state.failureCode(),
                state.failureMessage());
        return BacktestRunMapper.toDomain(jpaRepository.save(entity));
    }

    @Override
    public Optional<BacktestRun> findById(Long id) {
        return jpaRepository.findById(id).map(BacktestRunMapper::toDomain);
    }

    @Override
    public Optional<BacktestRun> findByBacktestId(String backtestId) {
        return jpaRepository.findByBacktestId(backtestId).map(BacktestRunMapper::toDomain);
    }
}
