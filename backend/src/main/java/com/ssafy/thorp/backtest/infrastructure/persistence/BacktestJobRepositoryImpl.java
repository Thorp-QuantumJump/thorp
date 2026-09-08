package com.ssafy.thorp.backtest.infrastructure.persistence;

import com.ssafy.thorp.backtest.application.repository.BacktestJobRepository;
import com.ssafy.thorp.backtest.domain.model.BacktestJob;
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
public class BacktestJobRepositoryImpl implements BacktestJobRepository {

    private final BacktestJobJpaRepository jpaRepository;
    private final Clock clock;

    public BacktestJobRepositoryImpl(BacktestJobJpaRepository jpaRepository, Clock clock) {
        this.jpaRepository = jpaRepository;
        this.clock = clock;
    }

    @Override
    public BacktestJob save(BacktestJob job) {
        if (job.getId() != null) {
            throw new IllegalArgumentException("save is for new jobs only: id=" + job.getId());
        }
        BacktestJobJpaEntity entity = BacktestJobMapper.toEntity(job);
        entity.assignBacktestIdOn(LocalDate.now(clock));
        return BacktestJobMapper.toDomain(jpaRepository.save(entity));
    }

    @Override
    public Optional<BacktestJob> findById(Long id) {
        return jpaRepository.findById(id).map(BacktestJobMapper::toDomain);
    }

    @Override
    public Optional<BacktestJob> findByBacktestId(String backtestId) {
        return jpaRepository.findByBacktestId(backtestId).map(BacktestJobMapper::toDomain);
    }
}
