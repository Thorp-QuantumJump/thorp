package com.ssafy.thorp.paramsearch.application.service.limit;

import com.ssafy.thorp.paramsearch.application.port.SearchLimits;
import com.ssafy.thorp.paramsearch.application.repository.SearchCandidateRepository;
import com.ssafy.thorp.paramsearch.domain.model.CandidateStatus;
import com.ssafy.thorp.paramsearch.domain.model.SearchCandidate;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 연속 실패 한도(FR-005, 내부 계약 §4 "한도 도달").
 *
 * <p><b>누적 실패가 아니라 연속 실패다.</b> 자료 결측이나 설정 오류처럼 <b>남은 후보도 같은 이유로 실패할</b> 상황을 끊는 것이 목적이라, 중간에 성공이
 * 하나라도 있으면 그 상황이 아니다. 누적으로 세면 후보가 많은 묶음에서 드문드문 난 실패가 쌓여 멀쩡한 탐색이 멈춘다.
 *
 * <p><b>끝난 순서로 센다.</b> 순번 순서가 아니다 — 발행 창 안에서 먼저 나간 후보가 늦게 끝나는 일이 흔해서, 순번으로 세면 "줄줄이 실패했다"를 잘못 읽는다.
 *
 * <p>한도는 <b>발행 창보다 커야 한다.</b> 창에 든 후보가 한꺼번에 실패하는 일만으로 묶음이 멈추면, 일시적인 브로커·자료 문제에 탐색 전체가 끊긴다
 * (application.yml 주석). 그 관계는 설정이 지키고 여기서는 값을 그대로 쓴다.
 *
 * <p>{@code consecutiveFailureLimit}은 <b>임시값</b>이다(PD-05 대기). 응답의 {@code limits.provisional}이 그 사실을
 * 화면에 나른다(FR-005a).
 */
@Component
@ConditionalOnProperty(prefix = "thorp.param-search", name = "enabled", havingValue = "true")
public class FailureLimitGuard {

    private final SearchCandidateRepository candidateRepository;
    private final SearchLimits limits;

    public FailureLimitGuard(SearchCandidateRepository candidateRepository, SearchLimits limits) {
        this.candidateRepository = candidateRepository;
        this.limits = limits;
    }

    /** 마지막으로 끝난 후보들이 한도만큼 내리 실패했는가. */
    public boolean isReached(Long paramSearchId) {
        int limit = limits.consecutiveFailureLimit();
        List<SearchCandidate> recent =
                candidateRepository.findRecentlyFinished(paramSearchId, limit);
        if (recent.size() < limit) {
            // 끝난 후보가 한도보다 적으면 "연속 N건"이 성립하지 않는다.
            return false;
        }
        return recent.stream()
                .allMatch(candidate -> candidate.getStatus() == CandidateStatus.FAILED);
    }
}
