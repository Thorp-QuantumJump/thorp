package com.ssafy.thorp.paramsearch.application.service;

import com.ssafy.thorp.common.exception.BusinessException;
import com.ssafy.thorp.common.exception.ErrorCode;
import com.ssafy.thorp.paramsearch.application.port.CandidateJobLauncher;
import com.ssafy.thorp.paramsearch.application.port.ParamSearchSnapshot;
import com.ssafy.thorp.paramsearch.application.port.SearchLimits;
import com.ssafy.thorp.paramsearch.application.repository.ParamSearchRepository;
import com.ssafy.thorp.paramsearch.application.repository.SearchCandidateRepository;
import com.ssafy.thorp.paramsearch.application.result.ParamSearchRetryResult;
import com.ssafy.thorp.paramsearch.domain.model.CandidateStatus;
import com.ssafy.thorp.paramsearch.domain.model.ParamSearch;
import com.ssafy.thorp.paramsearch.domain.model.SearchCandidate;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 실패 후보 재시도(FR-024, contracts/param-search-api.md §5.1).
 *
 * <p><b>새 묶음을 만들지 않는다.</b> 참조 버전·탐색 공간·선정 규칙을 접수 시점 스냅샷 그대로 쓴다 — 다시 돌린 후보의 결과가 먼저 끝난 후보들과 같은 조건이어야
 * 나란히 놓을 수 있다(FR-012). 새 묶음으로 만들면 그 비교가 성립하지 않고, 사용자는 같은 탐색의 결과가 두 곳에 나뉜 것을 보게 된다.
 *
 * <p><b>{@code SKIPPED}는 대상이 아니다.</b> 취소·한도로 빠진 후보를 재시도로 되살리면 "여기까지 계산했다"는 완료 범위 표시가 뜻을 잃는다. 재시도는
 * {@code FAILED}에서만 출발한다(data-model §4.1).
 *
 * <p><b>발행 창을 넘겨 보내지 않는다</b>(내부 계약 §4 불변식 1). 실패가 창보다 많으면 이번에 창만큼만 보내고 나머지는 {@code FAILED}로 둔다 —
 * 자동으로 이어 보내지 않는다. 반복 재시도는 사용자가 다시 요청할 때만 일어난다(계약 §5.1).
 */
@Service
@ConditionalOnProperty(prefix = "thorp.param-search", name = "enabled", havingValue = "true")
public class ParamSearchRetryService {

    private final ParamSearchRepository paramSearchRepository;
    private final SearchCandidateRepository candidateRepository;
    private final CandidateJobLauncher launcher;
    private final SearchLimits limits;

    public ParamSearchRetryService(
            ParamSearchRepository paramSearchRepository,
            SearchCandidateRepository candidateRepository,
            CandidateJobLauncher launcher,
            SearchLimits limits) {
        this.paramSearchRepository = paramSearchRepository;
        this.candidateRepository = candidateRepository;
        this.launcher = launcher;
        this.limits = limits;
    }

    /**
     * 실패 후보를 다시 발행한다.
     *
     * @throws BusinessException 없는 묶음이면 404, 실패 후보가 없으면 409 {@code NOTHING_TO_RETRY}
     */
    @Transactional
    public ParamSearchRetryResult retryFailed(String searchId) {
        ParamSearch found =
                paramSearchRepository
                        .findBySearchId(searchId)
                        .orElseThrow(
                                () ->
                                        new BusinessException(
                                                ErrorCode.RESOURCE_NOT_FOUND,
                                                "탐색 묶음을 찾을 수 없습니다: " + searchId));
        ParamSearch locked = paramSearchRepository.findByIdForUpdate(found.getId()).orElseThrow();

        List<SearchCandidate> failed =
                candidateRepository.findByParamSearchIdAndStatus(
                        locked.getId(), CandidateStatus.FAILED);
        if (failed.isEmpty()) {
            // 되살릴 것이 없는데 202로 답하면 사용자는 다시 돌고 있다고 믿는다.
            throw new BusinessException(
                    ErrorCode.NOTHING_TO_RETRY, "다시 시도할 실패 후보가 없습니다: " + searchId);
        }

        ParamSearch reopened =
                paramSearchRepository.update(
                        locked.getStatus().isFinal() ? locked.reopenForRetry() : locked);
        ParamSearchSnapshot snapshot = snapshotOf(reopened);

        int retried = dispatch(reopened, failed, snapshot);
        long remainingFailed =
                candidateRepository.countByStatus(reopened.getId(), CandidateStatus.FAILED);
        return new ParamSearchRetryResult(
                reopened.getSearchId().value(),
                reopened.getStatus().name(),
                retried,
                remainingFailed);
    }

    /**
     * 창의 빈자리만큼 다시 발행한다.
     *
     * <p>발행 실패(브로커 장애)는 후보를 {@code FAILED}로 남긴다 — 계산 실패와 발행 실패는 다르지만, 여기서는 이미 실패한 후보라 되돌릴 상태가 없다.
     * 다음 재시도 요청이 같은 후보를 다시 집는다.
     */
    private int dispatch(
            ParamSearch search, List<SearchCandidate> failed, ParamSearchSnapshot snapshot) {
        long inFlight =
                candidateRepository.countByStatus(search.getId(), CandidateStatus.DISPATCHED);
        int room = limits.dispatchWindow() - (int) inFlight;
        int retried = 0;
        for (SearchCandidate candidate : failed) {
            if (retried >= room) {
                break;
            }
            long backtestJobId = launcher.launchCandidate(snapshot, candidate);
            candidateRepository.update(candidate.markDispatched(backtestJobId));
            retried++;
        }
        return retried;
    }

    private static ParamSearchSnapshot snapshotOf(ParamSearch search) {
        return ParamSearchSnapshot.fromMap(
                search.getId(),
                search.getSearchId().value(),
                search.getOwnerUserId(),
                search.getExecutionPlan());
    }
}
