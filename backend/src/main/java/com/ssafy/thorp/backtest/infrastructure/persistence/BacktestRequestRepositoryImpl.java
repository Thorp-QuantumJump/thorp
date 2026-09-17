package com.ssafy.thorp.backtest.infrastructure.persistence;

import com.ssafy.thorp.backtest.application.repository.BacktestRequestRepository;
import com.ssafy.thorp.backtest.domain.model.BacktestRequest;
import com.ssafy.thorp.backtest.domain.model.PublicResultToken;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;

/**
 * 저장 시 외부 식별자를 부여한다. 방식은 {@link BacktestRunRepositoryImpl}과 같다 — SEQUENCE로 PK를 먼저 받고 그 값으로 {@code
 * backtest_id}를 INSERT 전에 채운다.
 */
@Repository
public class BacktestRequestRepositoryImpl implements BacktestRequestRepository {

    private final BacktestRequestJpaRepository jpaRepository;
    private final EntityManager entityManager;
    private final Clock clock;

    public BacktestRequestRepositoryImpl(
            BacktestRequestJpaRepository jpaRepository, EntityManager entityManager, Clock clock) {
        this.jpaRepository = jpaRepository;
        this.entityManager = entityManager;
        this.clock = clock;
    }

    @Override
    public BacktestRequest save(BacktestRequest request) {
        if (request.getId() != null) {
            throw new IllegalArgumentException(
                    "save is for new requests only: id=" + request.getId());
        }
        BacktestRequestJpaEntity entity = BacktestRequestMapper.toEntity(request);
        entity.assignBacktestIdOn(LocalDate.now(clock));
        return BacktestRequestMapper.toDomain(jpaRepository.save(entity));
    }

    /**
     * 관리 엔티티를 읽어 취소 기록만 바꾼다. 도메인 객체를 새 엔티티로 merge하면 {@code createdAt}(updatable=false)이 null인 사본이
     * 관리 상태를 덮어 쓰므로 그 방식은 피한다.
     */
    @Override
    public BacktestRequest update(BacktestRequest request) {
        if (request.getId() == null) {
            throw new IllegalArgumentException("update is for persisted requests only: id is null");
        }
        BacktestRequestJpaEntity entity =
                jpaRepository
                        .findById(request.getId())
                        .orElseThrow(
                                () ->
                                        new IllegalArgumentException(
                                                "request not found: id=" + request.getId()));
        entity.applyCancellation(
                request.getCancelledAt(),
                request.getCancelledLastTradingDate(),
                request.isStoppedRun());
        return BacktestRequestMapper.toDomain(jpaRepository.save(entity));
    }

    @Override
    public Optional<BacktestRequest> findByBacktestId(String backtestId) {
        return jpaRepository.findByBacktestId(backtestId).map(BacktestRequestMapper::toDomain);
    }

    /**
     * 잠그고 그 시점의 행을 다시 읽는다. 취소는 이 조회를 첫 읽기로 쓰지만 잠금 뒤 {@code refresh} 하는 이유는 {@link
     * BacktestRunRepositoryImpl#findByIdForUpdate} 와 같다 — 잠금 자체는 1차 캐시의 옛 값을 밀어내지 않는다.
     */
    @Override
    public Optional<BacktestRequest> findByBacktestIdForUpdate(String backtestId) {
        return jpaRepository
                .lockByBacktestId(backtestId)
                .map(
                        entity -> {
                            entityManager.refresh(entity);
                            return BacktestRequestMapper.toDomain(entity);
                        });
    }

    @Override
    public List<BacktestRequest> findByRunId(Long runId) {
        return jpaRepository.findByRunId(runId).stream()
                .map(BacktestRequestMapper::toDomain)
                .toList();
    }

    @Override
    public long countActiveByRunId(Long runId) {
        return jpaRepository.countByRunIdAndCancelledAtIsNull(runId);
    }

    @Override
    public Optional<BacktestRequest> findByPublicToken(PublicResultToken publicToken) {
        return jpaRepository
                .findByPublicToken(publicToken.value())
                .map(BacktestRequestMapper::toDomain);
    }

    @Override
    public List<BacktestRequest> findExpiredAnonymous(Instant now, int limit) {
        return jpaRepository.findExpiredAnonymous(now, PageRequest.of(0, limit)).stream()
                .map(BacktestRequestMapper::toDomain)
                .toList();
    }

    @Override
    public void deleteById(long id) {
        jpaRepository.deleteById(id);
    }
}
