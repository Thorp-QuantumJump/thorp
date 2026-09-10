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

    long countByParamSearchIdAndStatus(Long paramSearchId, CandidateStatus status);

    Optional<SearchCandidateJpaEntity> findByBacktestJobId(Long backtestJobId);
}
