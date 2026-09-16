package com.ssafy.thorp.paramsearch.application.service;

import com.ssafy.thorp.common.exception.BusinessException;
import com.ssafy.thorp.common.exception.ErrorCode;
import com.ssafy.thorp.paramsearch.application.repository.ParamSearchRepository;
import com.ssafy.thorp.paramsearch.application.repository.SearchCandidateRepository;
import com.ssafy.thorp.paramsearch.application.result.ParamSearchCancelResult;
import com.ssafy.thorp.paramsearch.domain.model.CandidateStatus;
import com.ssafy.thorp.paramsearch.domain.model.ParamSearch;
import com.ssafy.thorp.paramsearch.domain.model.SearchCandidate;
import com.ssafy.thorp.paramsearch.domain.model.StopReason;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 탐색 취소(FR-009, contracts/param-search-api.md §5, research R2).
 *
 * <p>취소가 하는 일은 둘이다 — <b>아직 발행하지 않은 후보를 '미실행'으로 확정</b>하고, <b>더 발행하지 않는다</b>. 이미 큐에 들어간 후보는 완주한다:
 * prefetch 1 + manual ACK 구조에서 발행된 메시지는 회수할 수 없다. 그래서 취소는 "즉시 멈춤"이 아니라 "여기까지만"이다.
 *
 * <p><b>부분 결과를 버리지 않는다</b>(FR-009). 취소된 묶음도 완료된 후보끼리는 같은 조건으로 계산됐으므로 그대로 비교할 수 있다 — 그래서 {@code
 * PARTIAL}도 선정을 계산한다(data-model §3).
 *
 * <p>묶음 행을 비관적 락으로 잡는 이유: 취소는 HTTP 스레드에서 오고 후보 종료는 이벤트 리스너 스레드에서 온다. 락 없이 하면 취소가 {@code PENDING}을
 * 세는 사이에 리스너가 그 후보를 발행해, 취소한 뒤에 나간 후보가 생긴다(내부 계약 §4 불변식 2).
 */
@Service
@ConditionalOnProperty(prefix = "thorp.param-search", name = "enabled", havingValue = "true")
public class ParamSearchCancelService {

    private final ParamSearchRepository paramSearchRepository;
    private final SearchCandidateRepository candidateRepository;
    private final ParamSearchCompletionService completionService;

    public ParamSearchCancelService(
            ParamSearchRepository paramSearchRepository,
            SearchCandidateRepository candidateRepository,
            ParamSearchCompletionService completionService) {
        this.paramSearchRepository = paramSearchRepository;
        this.candidateRepository = candidateRepository;
        this.completionService = completionService;
    }

    /**
     * 묶음을 취소한다.
     *
     * <p>남의 묶음은 403이 아니라 <b>404</b>다(spec 004 R-12와 같은 규칙) — 403은 그 식별자가 존재한다는 사실을 알려 준다. {@code
     * searchId}는 날짜+순번이라 추측할 수 있다.
     *
     * @throws BusinessException 없거나 남의 묶음이면 404, 이미 끝난 묶음이면 409 {@code ALREADY_FINISHED}
     */
    @Transactional
    public ParamSearchCancelResult cancel(String searchId, long requesterId) {
        ParamSearch found =
                paramSearchRepository
                        .findBySearchId(searchId)
                        .orElseThrow(
                                () ->
                                        new BusinessException(
                                                ErrorCode.RESOURCE_NOT_FOUND,
                                                "탐색 묶음을 찾을 수 없습니다: " + searchId));
        ParamSearch locked = paramSearchRepository.findByIdForUpdate(found.getId()).orElseThrow();
        requireOwner(locked, requesterId, searchId);
        if (locked.getStatus().isFinal()) {
            // 끝난 것은 멈출 수 없다. 조용히 성공으로 답하면 사용자는 멈췄다고 믿는다.
            throw new BusinessException(ErrorCode.ALREADY_FINISHED, "이미 끝난 탐색입니다: " + searchId);
        }

        int skipped = skipPending(locked.getId());
        long inFlight =
                candidateRepository.countByStatus(locked.getId(), CandidateStatus.DISPATCHED);

        ParamSearch stopped =
                paramSearchRepository.update(locked.requestStop(StopReason.CANCELLED));
        ParamSearch settled = completionService.settle(stopped);

        return new ParamSearchCancelResult(
                settled.getSearchId().value(), settled.getStatus().name(), skipped, inFlight);
    }

    /** 소유자 확인은 잠근 객체로 한다 — 잠그기 전 사본은 그 사이 바뀌었을 수 있다. 존재 여부를 흘리지 않으려고 없는 묶음과 같은 응답을 쓴다. */
    private static void requireOwner(ParamSearch locked, long requesterId, String searchId) {
        if (!locked.ownedBy(requesterId)) {
            throw new BusinessException(
                    ErrorCode.RESOURCE_NOT_FOUND, "탐색 묶음을 찾을 수 없습니다: " + searchId);
        }
    }

    /** 아직 발행하지 않은 후보를 '미실행'으로 확정한다. 되돌아오지 않는 상태다 — 재시도 대상도 아니다(FR-024). */
    private int skipPending(Long paramSearchId) {
        List<SearchCandidate> pending =
                candidateRepository.findByParamSearchIdAndStatus(
                        paramSearchId, CandidateStatus.PENDING);
        for (SearchCandidate candidate : pending) {
            candidateRepository.update(candidate.markSkipped("CANCELLED", "사용자가 취소해 실행되지 않았습니다"));
        }
        return pending.size();
    }
}
