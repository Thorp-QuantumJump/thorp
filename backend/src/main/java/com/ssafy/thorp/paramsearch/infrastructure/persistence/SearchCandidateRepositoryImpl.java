package com.ssafy.thorp.paramsearch.infrastructure.persistence;

import com.ssafy.thorp.paramsearch.application.repository.SearchCandidateRepository;
import com.ssafy.thorp.paramsearch.domain.model.CandidateStatus;
import com.ssafy.thorp.paramsearch.domain.model.SearchCandidate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Repository;

/**
 * 후보 저장·조회. 접수 시점의 대량 INSERT와 진행 중의 건별 갱신 두 경로가 있다.
 *
 * <p>대량 INSERT는 {@code saveAll}로 한 번에 보낸다 — 엔티티의 {@code allocationSize = 50}과 {@code
 * spring.jpa.properties.hibernate.jdbc.batch_size = 50}이 이 경로를 위한 설정이다. 건당 저장이면 후보 1000건에 왕복
 * 1000번이다.
 */
@Repository
public class SearchCandidateRepositoryImpl implements SearchCandidateRepository {

    private final SearchCandidateJpaRepository jpaRepository;

    public SearchCandidateRepositoryImpl(SearchCandidateJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    public List<SearchCandidate> saveAll(Long paramSearchId, List<SearchCandidate> candidates) {
        if (paramSearchId == null) {
            throw new IllegalArgumentException("paramSearchId must not be null");
        }
        if (candidates.stream().anyMatch(candidate -> candidate.getId() != null)) {
            throw new IllegalArgumentException("saveAll is for new candidates only");
        }
        List<SearchCandidateJpaEntity> entities =
                candidates.stream()
                        .map(candidate -> SearchCandidateMapper.toEntity(candidate, paramSearchId))
                        .toList();
        return jpaRepository.saveAll(entities).stream()
                .map(SearchCandidateMapper::toDomain)
                .toList();
    }

    /** 관리 엔티티를 읽어 진행 상태만 바꾼다. 이유는 {@link ParamSearchRepositoryImpl#update} 주석과 같다. */
    @Override
    public SearchCandidate update(SearchCandidate candidate) {
        if (candidate.getId() == null) {
            throw new IllegalArgumentException(
                    "update is for persisted candidates only: id is null");
        }
        SearchCandidateJpaEntity entity =
                jpaRepository
                        .findById(candidate.getId())
                        .orElseThrow(
                                () ->
                                        new IllegalArgumentException(
                                                "candidate not found: id=" + candidate.getId()));
        entity.applyProgress(
                candidate.getBacktestJobId(),
                candidate.getStatus(),
                candidate.getReasonCode(),
                candidate.getReasonDetail(),
                candidate.getStability(),
                candidate.getRetryCount(),
                candidate.getPeriodPerformance(),
                candidate.getValidationLabel(),
                candidate.isSelected(),
                candidate.getSelectionReason());
        return SearchCandidateMapper.toDomain(jpaRepository.save(entity));
    }

    @Override
    public List<SearchCandidate> findByParamSearchId(Long paramSearchId) {
        return jpaRepository.findByParamSearchIdOrderByCandidateNoAsc(paramSearchId).stream()
                .map(SearchCandidateMapper::toDomain)
                .toList();
    }

    @Override
    public List<SearchCandidate> findByParamSearchIdAndStatus(
            Long paramSearchId, CandidateStatus status) {
        return jpaRepository
                .findByParamSearchIdAndStatusOrderByCandidateNoAsc(paramSearchId, status)
                .stream()
                .map(SearchCandidateMapper::toDomain)
                .toList();
    }

    @Override
    public List<SearchCandidate> findNextByStatus(
            Long paramSearchId, CandidateStatus status, int limit) {
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be positive: " + limit);
        }
        return jpaRepository
                .findByParamSearchIdAndStatusOrderByCandidateNoAsc(
                        paramSearchId, status, Limit.of(limit))
                .stream()
                .map(SearchCandidateMapper::toDomain)
                .toList();
    }

    @Override
    public List<SearchCandidate> findRecentlyFinished(Long paramSearchId, int limit) {
        return jpaRepository
                .findByParamSearchIdAndStatusInOrderByUpdatedAtDesc(
                        paramSearchId,
                        List.of(CandidateStatus.COMPLETED, CandidateStatus.FAILED),
                        Limit.of(limit))
                .stream()
                .map(SearchCandidateMapper::toDomain)
                .toList();
    }

    @Override
    public long countByStatus(Long paramSearchId, CandidateStatus status) {
        return jpaRepository.countByParamSearchIdAndStatus(paramSearchId, status);
    }

    @Override
    public Optional<SearchCandidate> findByBacktestJobId(Long backtestJobId) {
        return jpaRepository
                .findByBacktestJobId(backtestJobId)
                .map(SearchCandidateMapper::toDomain);
    }
}
