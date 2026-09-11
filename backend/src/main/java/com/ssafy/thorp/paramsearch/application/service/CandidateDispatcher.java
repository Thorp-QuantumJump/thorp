package com.ssafy.thorp.paramsearch.application.service;

import com.ssafy.thorp.paramsearch.application.port.CandidateJobLauncher;
import com.ssafy.thorp.paramsearch.application.port.ParamSearchSnapshot;
import com.ssafy.thorp.paramsearch.application.port.SearchLimits;
import com.ssafy.thorp.paramsearch.application.repository.ParamSearchRepository;
import com.ssafy.thorp.paramsearch.application.repository.SearchCandidateRepository;
import com.ssafy.thorp.paramsearch.domain.model.CandidateStatus;
import com.ssafy.thorp.paramsearch.domain.model.ParamSearch;
import com.ssafy.thorp.paramsearch.domain.model.ParamSearchStatus;
import com.ssafy.thorp.paramsearch.domain.model.SearchCandidate;
import java.time.Clock;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * 발행 창 관리(research R2, contracts/param-search-internal.md §4).
 *
 * <p><b>접수 시점에 후보 전량을 발행하지 않는다.</b> 이유는 둘이다 — 한 묶음이 {@code backtest.jobs}를 독점하면 다른 사용자의 단일 실행이 뒤로
 * 밀리고, 취소를 "다음 후보를 발행하지 않는다"는 동작으로 만들 수 없다(이미 큐에 든 메시지는 회수할 수 없다).
 *
 * <p>불변식: 한 묶음의 {@code DISPATCHED} 후보 수는 항상 발행 창 이하다. 동시에 도착한 결과 이벤트가 서로의 셈을 덮어쓰지 않도록 <b>묶음 행을 비관적
 * 락으로 잡고</b> 창을 채운다 — 락 없이 하면 두 이벤트가 각각 "창에 자리가 있다"고 판단해 창을 넘겨 발행한다.
 */
@Service
@ConditionalOnProperty(prefix = "thorp.param-search", name = "enabled", havingValue = "true")
public class CandidateDispatcher {

    private static final Logger log = LoggerFactory.getLogger(CandidateDispatcher.class);

    private final CandidateJobLauncher launcher;
    private final ParamSearchRepository paramSearchRepository;
    private final SearchCandidateRepository candidateRepository;
    private final SearchLimits limits;
    private final Clock clock;

    public CandidateDispatcher(
            CandidateJobLauncher launcher,
            ParamSearchRepository paramSearchRepository,
            SearchCandidateRepository candidateRepository,
            SearchLimits limits,
            Clock clock) {
        this.launcher = launcher;
        this.paramSearchRepository = paramSearchRepository;
        this.candidateRepository = candidateRepository;
        this.limits = limits;
        this.clock = clock;
    }

    /**
     * 접수 직후 첫 발행. 기준 1건을 먼저 보내고 창만큼 후보를 발행한다.
     *
     * <p>기준을 먼저 보내는 이유: 하락 폭 필터의 기준값이 없으면 선정 계산이 성립하지 않는다(FR-013d). 기준 전략 지정이 없으면 건너뛰고 그 사실을 선정 규칙
     * 스냅샷이 이미 담고 있다.
     */
    public ParamSearch start(ParamSearch saved, ParamSearchSnapshot snapshot) {
        ParamSearch running = paramSearchRepository.update(saved.markRunning(clock.instant()));
        if (snapshot.baselineStrategyId() != null) {
            running =
                    paramSearchRepository.update(
                            running.withBaselineJobId(launcher.launchBaseline(snapshot)));
        }
        fillWindow(running.getId(), snapshot);
        return paramSearchRepository.findById(running.getId()).orElse(running);
    }

    /**
     * 후보 하나가 끝났을 때 창을 다시 채운다. 묶음 행을 잠그고 세므로 동시 수신에서도 창을 넘지 않는다.
     *
     * <p>이미 끝난 묶음이면 아무것도 하지 않는다 — 취소·한도 도달 후에는 새 후보가 발행되지 않는다(불변식 2).
     */
    public void advance(Long paramSearchId, ParamSearchSnapshot snapshot) {
        ParamSearch locked = paramSearchRepository.findByIdForUpdate(paramSearchId).orElse(null);
        if (locked == null || locked.getStatus().isFinal()) {
            return;
        }
        fillWindow(paramSearchId, snapshot);
    }

    /**
     * 창의 빈자리만큼 발행한다. <b>락은 호출자가 잡는다</b> — {@link #advance}가 묶음 행을 잠근 뒤 부른다.
     *
     * <p>패키지 안에서만 보이게 둔 이유는 이 불변식(창을 넘지 않는다)을 락 없이도 단위 테스트로 고정하기 위해서다.
     */
    void fillWindow(Long paramSearchId, ParamSearchSnapshot snapshot) {
        long inFlight =
                candidateRepository.countByStatus(paramSearchId, CandidateStatus.DISPATCHED);
        int room = limits.dispatchWindow() - (int) inFlight;
        if (room <= 0) {
            return;
        }
        List<SearchCandidate> next =
                candidateRepository.findNextByStatus(paramSearchId, CandidateStatus.PENDING, room);
        for (SearchCandidate candidate : next) {
            dispatch(snapshot, candidate);
        }
    }

    /**
     * 후보 하나를 발행한다.
     *
     * <p>발행 실패(브로커 장애)는 후보를 {@code PENDING}으로 남긴다 — 계산 실패와 발행 실패는 다르다(FR-011). {@code FAILED}로 바꾸면
     * "이 조합은 계산할 수 없다"는 뜻이 되어 결과 해석이 틀어지고, 재시도 대상 셈도 어긋난다.
     */
    private void dispatch(ParamSearchSnapshot snapshot, SearchCandidate candidate) {
        try {
            long backtestJobId = launcher.launchCandidate(snapshot, candidate);
            candidateRepository.update(candidate.markDispatched(backtestJobId));
        } catch (RuntimeException e) {
            log.warn(
                    "candidate dispatch failed, staying PENDING: searchId={} candidateNo={}",
                    snapshot.searchId(),
                    candidate.getCandidateNo(),
                    e);
        }
    }

    /** 묶음이 끝났는가 — 대기도 실행 중도 없으면 끝이다(내부 계약 §4 "묶음 종료"). */
    public boolean isDrained(Long paramSearchId) {
        return candidateRepository.countByStatus(paramSearchId, CandidateStatus.PENDING) == 0
                && candidateRepository.countByStatus(paramSearchId, CandidateStatus.DISPATCHED)
                        == 0;
    }

    /** 상태가 아직 진행 중인가. 종료 판정 전에 확인한다. */
    public static boolean isRunning(ParamSearchStatus status) {
        return status == ParamSearchStatus.RUNNING || status == ParamSearchStatus.QUEUED;
    }
}
