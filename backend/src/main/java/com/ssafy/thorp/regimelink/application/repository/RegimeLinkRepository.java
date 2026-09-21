package com.ssafy.thorp.regimelink.application.repository;

import com.ssafy.thorp.regimelink.domain.model.LinkFailure;
import com.ssafy.thorp.regimelink.domain.model.RegimeLink;
import com.ssafy.thorp.regimelink.domain.vo.CaseRef;
import com.ssafy.thorp.regimelink.domain.vo.LinkId;
import com.ssafy.thorp.regimelink.domain.vo.LinkStatus;
import com.ssafy.thorp.regimelink.domain.vo.Scenario;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * (실행 × 사례 × 시나리오) 계산 저장소 (data-model §4).
 *
 * <p>{@code UNIQUE (run_id, snapshot_key, end_month, scenario)}가 "같은 단위는 다시 계산하지 않는다"(FR-010)를 스키마로
 * 강제하므로, 만들기 전에 {@link #findUnit}으로 먼저 찾는다.
 */
public interface RegimeLinkRepository {

    Optional<RegimeLink> findByLinkId(LinkId linkId);

    /** 이미 그 단위의 계산이 있는가. 있으면 상태와 결과를 그대로 쓴다. */
    Optional<RegimeLink> findUnit(Long runId, CaseRef caseRef, Scenario scenario);

    List<RegimeLink> findByRunId(Long runId);

    /**
     * 새 계산을 남긴다.
     *
     * <p><b>갱신이 아니다.</b> 이미 저장된 링크를 넘기면 같은 단위로 다시 넣으려다 {@code UNIQUE (run_id, snapshot_key,
     * end_month, scenario)} 에 걸린다. 상태 전이(발행·이벤트 반영)는 한 행을 여러 경로가 쓰므로 컬럼 소유를 가른 메서드로 따로 둔다 — 004
     * {@code BacktestRunRepository} 와 같은 이유이며 그 경로를 만드는 것은 M5다.
     */
    RegimeLink create(RegimeLink link);

    Optional<RegimeLink> findById(Long id);

    /** 사례 하나에 붙은 링크를 실행별로. 비교 묶음 조회(contracts §4)가 쓴다. */
    List<RegimeLink> findByCase(CaseRef caseRef, List<Long> runIds);

    /** 그 상태의 링크 전부. {@code PENDING_RUN} 감시(T033)가 쓴다. */
    List<RegimeLink> findByStatus(LinkStatus status);

    /**
     * 발행 경로가 쓰는 컬럼만 쓴다 — {@code status=QUEUED}·{@code job_message_id}·{@code attempt}·{@code
     * queued_at}.
     *
     * <p>이벤트 경로와 컬럼 소유를 가르는 것은 004 {@code BacktestRunRepository}와 같은 이유다. 한 행을 통째로 저장하면 늦게 저장한 쪽이
     * 상대의 갱신을 되돌린다.
     */
    void recordPublished(Long id, String jobMessageId, int attempt, Instant queuedAt);

    /** {@code STARTED} 반영. 이미 종결된 링크는 건드리지 않는다. */
    void markRunning(Long id, Instant startedAt);

    /** {@code FINISHED(COMPLETED)} 반영. 결과 저장과 같은 트랜잭션에서 부른다. */
    void markCompleted(Long id, Instant finishedAt);

    /** {@code FINISHED(FAILED)}·DLQ 반영. */
    void markFailed(Long id, LinkFailure failure, Instant finishedAt);

    /**
     * 새 004 실행을 기다리는 상태로 되돌린다 (전체 기간 재계산·현금 시작·{@code RUN_FAILED} 재시도).
     *
     * <p>{@code run_id}가 바뀌므로 {@code UNIQUE (run_id, snapshot_key, end_month, scenario)}를 다시 지나간다 —
     * 호출자는 그 단위의 링크가 이미 있는지 먼저 본다.
     */
    void markPendingRun(Long id, Long runId);
}
