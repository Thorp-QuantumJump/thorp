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
        if (locked == null || locked.getStatus().isFinal() || locked.isStopRequested()) {
            // 멈추기로 한 묶음에는 새 후보를 내보내지 않는다. 상태는 아직 RUNNING 이지만
            // 사유가 박혀 있으면 그것이 발행을 막는 근거다(불변식 2).
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
     * <p>후보를 {@code FAILED}로 바꾸지 않는다 — 계산 실패와 발행 실패는 다르다(FR-011). {@code FAILED}는 "이 조합은 계산할 수 없다"는
     * 뜻이라 결과 해석이 틀어지고 재시도 대상 셈도 어긋난다. 여기서 터지면 후보는 {@code PENDING}인 채로 남는다.
     *
     * <p><b>예외를 잡지 않는다.</b> 한때 {@code catch (RuntimeException)}으로 "발행 실패면 다음 후보로 넘어간다"를 의도했지만 그
     * 복원력은 없었다 — {@code launchCandidate} → 004 접수가 <b>같은 트랜잭션에 참여</b>하므로, 거기서 제약 위반이 나면 트랜잭션은 이미
     * rollback-only다. 잡아도 되살아나지 않고 커밋 시점에 {@code UnexpectedRollbackException}이 되어, 후보 하나의 실패가 묶음
     * 전체를 지운 채 500으로 나갔다.
     *
     * <p>이 catch가 노리던 브로커 장애는 애초에 여기로 오지 않는다. 004는 작업 메시지를 <b>커밋 뒤에</b> 보내고(spec 004 R-18), 보내지 못한
     * 실행은 {@code published_at NULL}로 남아 재발행기가 집어 간다. 브로커가 죽어도 접수는 성공하고 후보는 발행된 채로 기다린다.
     */
    private void dispatch(ParamSearchSnapshot snapshot, SearchCandidate candidate) {
        long backtestJobId = launcher.launchCandidate(snapshot, candidate);
        candidateRepository.update(candidate.markDispatched(backtestJobId));
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
