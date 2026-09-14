package com.ssafy.thorp.paramsearch.infrastructure.persistence;

import com.ssafy.thorp.paramsearch.domain.model.CandidateStatus;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;

interface SearchCandidateJpaRepository extends JpaRepository<SearchCandidateJpaEntity, Long> {

    List<SearchCandidateJpaEntity> findByParamSearchIdOrderByCandidateNoAsc(Long paramSearchId);

    List<SearchCandidateJpaEntity> findByParamSearchIdAndStatusOrderByCandidateNoAsc(
            Long paramSearchId, CandidateStatus status);

    /** 발행 창이 다음에 올릴 후보를 순번대로 가져온다. 창 크기만큼만 읽어 힙에 전량을 올리지 않는다. */
    List<SearchCandidateJpaEntity> findByParamSearchIdAndStatusOrderByCandidateNoAsc(
            Long paramSearchId, CandidateStatus status, Limit limit);

    /**
     * 최근에 끝난 후보부터. 연속 실패 한도가 "끝난 순서"를 봐야 해서 쓴다(FR-005).
     *
     * <p>{@code updatedAt}으로 정렬하는 이유: 후보 행에 종료 시각 컬럼이 따로 없고, 실행 중에 후보 행을 건드리는 것은 종료 처리뿐이라 그 값이 곧 끝난
     * 시각이다. {@code candidateNo} 순서로 대신할 수 없다 — 창 안에서 먼저 발행된 후보가 늦게 끝나는 일이 흔하다.
     */
    List<SearchCandidateJpaEntity> findByParamSearchIdAndStatusInOrderByUpdatedAtDesc(
            Long paramSearchId, List<CandidateStatus> statuses, Limit limit);

    long countByParamSearchIdAndStatus(Long paramSearchId, CandidateStatus status);

    Optional<SearchCandidateJpaEntity> findByBacktestJobId(Long backtestJobId);
}
