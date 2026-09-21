package com.ssafy.thorp.regimelink.infrastructure.persistence;

import com.ssafy.thorp.regimelink.application.repository.RegimeLinkRepository;
import com.ssafy.thorp.regimelink.domain.model.LinkFailure;
import com.ssafy.thorp.regimelink.domain.model.RegimeLink;
import com.ssafy.thorp.regimelink.domain.vo.CaseRef;
import com.ssafy.thorp.regimelink.domain.vo.LinkId;
import com.ssafy.thorp.regimelink.domain.vo.LinkStatus;
import com.ssafy.thorp.regimelink.domain.vo.Scenario;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/**
 * 계산 저장소 구현.
 *
 * <p>외부 식별자({@code RL-…})는 PK 가 나온 뒤에 짓는다 — 날짜와 순번으로 만들기 때문이다. 004 {@code BacktestId} 와 같은 방식이다.
 */
@Repository
public class RegimeLinkRepositoryImpl implements RegimeLinkRepository {

    private final RegimeLinkJpaRepository jpaRepository;
    private final Clock clock;

    public RegimeLinkRepositoryImpl(RegimeLinkJpaRepository jpaRepository, Clock clock) {
        this.jpaRepository = jpaRepository;
        this.clock = clock;
    }

    @Override
    public Optional<RegimeLink> findByLinkId(LinkId linkId) {
        return jpaRepository.findByLinkId(linkId.value()).map(RegimeLinkMapper::toDomain);
    }

    @Override
    public Optional<RegimeLink> findUnit(Long runId, CaseRef caseRef, Scenario scenario) {
        return jpaRepository
                .findByRunIdAndSnapshotKeyAndEndMonthAndScenario(
                        runId, caseRef.snapshotKey(), caseRef.endMonth().atDay(1), scenario.name())
                .map(RegimeLinkMapper::toDomain);
    }

    @Override
    public List<RegimeLink> findByRunId(Long runId) {
        return jpaRepository.findByRunId(runId).stream().map(RegimeLinkMapper::toDomain).toList();
    }

    @Override
    public RegimeLink create(RegimeLink link) {
        return RegimeLinkMapper.toDomain(
                jpaRepository.saveAndFlush(RegimeLinkMapper.toEntity(link, LocalDate.now(clock))));
    }

    @Override
    public Optional<RegimeLink> findById(Long id) {
        return jpaRepository.findById(id).map(RegimeLinkMapper::toDomain);
    }

    @Override
    public List<RegimeLink> findByCase(CaseRef caseRef, List<Long> runIds) {
        if (runIds.isEmpty()) {
            return List.of();
        }
        return jpaRepository
                .findBySnapshotKeyAndEndMonthAndRunIdIn(
                        caseRef.snapshotKey(), caseRef.endMonth().atDay(1), runIds)
                .stream()
                .map(RegimeLinkMapper::toDomain)
                .toList();
    }

    @Override
    public List<RegimeLink> findByStatus(LinkStatus status) {
        return jpaRepository.findByStatus(status.name()).stream()
                .map(RegimeLinkMapper::toDomain)
                .toList();
    }

    @Override
    public void recordPublished(Long id, String jobMessageId, int attempt, Instant queuedAt) {
        jpaRepository
                .findById(id)
                .ifPresent(entity -> entity.applyPublished(jobMessageId, attempt, queuedAt));
    }

    @Override
    public void markRunning(Long id, Instant startedAt) {
        jpaRepository.findById(id).ifPresent(entity -> entity.applyRunning(startedAt));
    }

    @Override
    public void markCompleted(Long id, Instant finishedAt) {
        jpaRepository.findById(id).ifPresent(entity -> entity.applyCompleted(finishedAt));
    }

    @Override
    public void markFailed(Long id, LinkFailure failure, Instant finishedAt) {
        Map<String, Object> value = JsonValues.map();
        JsonValues.put(value, "code", failure.code());
        JsonValues.put(value, "message", failure.message());
        jpaRepository.findById(id).ifPresent(entity -> entity.applyFailed(value, finishedAt));
    }

    @Override
    public void markPendingRun(Long id, Long runId) {
        jpaRepository.findById(id).ifPresent(entity -> entity.applyPendingRun(runId));
    }
}
