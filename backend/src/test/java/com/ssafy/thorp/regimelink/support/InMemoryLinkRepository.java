package com.ssafy.thorp.regimelink.support;

import com.ssafy.thorp.regimelink.application.repository.RegimeLinkRepository;
import com.ssafy.thorp.regimelink.domain.model.LinkFailure;
import com.ssafy.thorp.regimelink.domain.model.RegimeLink;
import com.ssafy.thorp.regimelink.domain.vo.CaseRef;
import com.ssafy.thorp.regimelink.domain.vo.LinkId;
import com.ssafy.thorp.regimelink.domain.vo.LinkStatus;
import com.ssafy.thorp.regimelink.domain.vo.Scenario;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 계산 저장소의 시험용 구현.
 *
 * <p><b>컬럼 소유를 가른 갱신 메서드를 그대로 흉내 낸다</b> — 상태 전이마다 그 경로가 쓰는 값만 바꾼다. 통째로 덮어쓰게 두면 실제 저장소가 막는 사고(늦게 저장한
 * 쪽이 상대의 갱신을 되돌리는 것)를 시험이 잡지 못한다.
 */
public class InMemoryLinkRepository implements RegimeLinkRepository {

    private final Map<Long, RegimeLink> stored = new LinkedHashMap<>();
    private final AtomicLong sequence = new AtomicLong();

    @Override
    public Optional<RegimeLink> findByLinkId(LinkId linkId) {
        return stored.values().stream().filter(link -> linkId.equals(link.linkId())).findFirst();
    }

    @Override
    public Optional<RegimeLink> findUnit(Long runId, CaseRef caseRef, Scenario scenario) {
        return stored.values().stream()
                .filter(
                        link ->
                                link.runId().equals(runId)
                                        && link.caseRef().equals(caseRef)
                                        && link.scenario() == scenario)
                .findFirst();
    }

    @Override
    public List<RegimeLink> findByRunId(Long runId) {
        return stored.values().stream().filter(link -> link.runId().equals(runId)).toList();
    }

    @Override
    public RegimeLink create(RegimeLink link) {
        long id = sequence.incrementAndGet();
        RegimeLink saved = with(link, id, LinkId.of(LocalDate.of(2026, 9, 21), id));
        stored.put(id, saved);
        return saved;
    }

    @Override
    public Optional<RegimeLink> findById(Long id) {
        return Optional.ofNullable(stored.get(id));
    }

    @Override
    public List<RegimeLink> findByCase(CaseRef caseRef, List<Long> runIds) {
        List<RegimeLink> found = new ArrayList<>();
        for (RegimeLink link : stored.values()) {
            if (link.caseRef().equals(caseRef) && runIds.contains(link.runId())) {
                found.add(link);
            }
        }
        return found;
    }

    @Override
    public List<RegimeLink> findByStatus(LinkStatus status) {
        return stored.values().stream().filter(link -> link.status() == status).toList();
    }

    @Override
    public void recordPublished(Long id, String jobMessageId, int attempt, Instant queuedAt) {
        update(
                id,
                link ->
                        copy(
                                link,
                                LinkStatus.QUEUED,
                                link.failure(),
                                jobMessageId,
                                attempt,
                                queuedAt,
                                link.startedAt(),
                                link.finishedAt(),
                                link.runId()));
    }

    @Override
    public void markRunning(Long id, Instant startedAt) {
        update(
                id,
                link ->
                        copy(
                                link,
                                LinkStatus.RUNNING,
                                link.failure(),
                                link.jobMessageId(),
                                link.attempt(),
                                link.queuedAt(),
                                startedAt,
                                link.finishedAt(),
                                link.runId()));
    }

    @Override
    public void markCompleted(Long id, Instant finishedAt) {
        update(
                id,
                link ->
                        copy(
                                link,
                                LinkStatus.COMPLETED,
                                null,
                                link.jobMessageId(),
                                link.attempt(),
                                link.queuedAt(),
                                link.startedAt(),
                                finishedAt,
                                link.runId()));
    }

    @Override
    public void markFailed(Long id, LinkFailure failure, Instant finishedAt) {
        update(
                id,
                link ->
                        copy(
                                link,
                                LinkStatus.FAILED,
                                failure,
                                link.jobMessageId(),
                                link.attempt(),
                                link.queuedAt(),
                                link.startedAt(),
                                finishedAt,
                                link.runId()));
    }

    @Override
    public void markPendingRun(Long id, Long runId) {
        update(
                id,
                link ->
                        copy(
                                link,
                                LinkStatus.PENDING_RUN,
                                null,
                                link.jobMessageId(),
                                link.attempt(),
                                link.queuedAt(),
                                link.startedAt(),
                                null,
                                runId));
    }

    public int size() {
        return stored.size();
    }

    private void update(Long id, java.util.function.UnaryOperator<RegimeLink> change) {
        RegimeLink link = stored.get(id);
        if (link != null) {
            stored.put(id, change.apply(link));
        }
    }

    private static RegimeLink with(RegimeLink link, Long id, LinkId linkId) {
        return new RegimeLink(
                id,
                linkId,
                link.runId(),
                link.caseRef(),
                link.scenario(),
                link.postWindow(),
                link.availability(),
                link.status(),
                link.failure(),
                link.jobMessageId(),
                link.attempt(),
                link.queuedAt(),
                link.startedAt(),
                link.finishedAt(),
                link.createdAt(),
                link.updatedAt());
    }

    private static RegimeLink copy(
            RegimeLink link,
            LinkStatus status,
            LinkFailure failure,
            String jobMessageId,
            int attempt,
            Instant queuedAt,
            Instant startedAt,
            Instant finishedAt,
            Long runId) {
        return new RegimeLink(
                link.id(),
                link.linkId(),
                runId,
                link.caseRef(),
                link.scenario(),
                link.postWindow(),
                link.availability(),
                status,
                failure,
                jobMessageId,
                attempt,
                queuedAt,
                startedAt,
                finishedAt,
                link.createdAt(),
                link.updatedAt());
    }
}
