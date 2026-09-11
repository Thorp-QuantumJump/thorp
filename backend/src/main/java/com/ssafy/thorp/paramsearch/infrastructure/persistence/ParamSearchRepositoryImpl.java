package com.ssafy.thorp.paramsearch.infrastructure.persistence;

import com.ssafy.thorp.paramsearch.application.repository.ParamSearchRepository;
import com.ssafy.thorp.paramsearch.domain.model.ParamSearch;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/**
 * 저장 시 외부 식별자를 부여한다.
 *
 * <p>{@code save()}는 신규 엔티티에 {@code persist()}를 호출하고, SEQUENCE라 그 시점에 PK가 나온다. 그 PK로 {@code
 * search_id}를 INSERT 전에 채우므로 INSERT는 한 번이고 컬럼을 NOT NULL로 둘 수 있다. 날짜만 여기서 정하고 PK와의 결합은 엔티티의
 * {@code @PrePersist}가 한다. {@code BacktestJobRepositoryImpl}과 같은 규약이다.
 */
@Repository
public class ParamSearchRepositoryImpl implements ParamSearchRepository {

    private final ParamSearchJpaRepository jpaRepository;
    private final Clock clock;

    public ParamSearchRepositoryImpl(ParamSearchJpaRepository jpaRepository, Clock clock) {
        this.jpaRepository = jpaRepository;
        this.clock = clock;
    }

    @Override
    public ParamSearch save(ParamSearch paramSearch) {
        if (paramSearch.getId() != null) {
            throw new IllegalArgumentException(
                    "save is for new searches only: id=" + paramSearch.getId());
        }
        ParamSearchJpaEntity entity = ParamSearchMapper.toEntity(paramSearch);
        entity.assignSearchIdOn(LocalDate.now(clock));
        return ParamSearchMapper.toDomain(jpaRepository.save(entity));
    }

    /**
     * 관리 엔티티를 읽어 진행 상태만 바꾼다.
     *
     * <p>도메인 객체를 새 엔티티로 merge하면 {@code createdAt}(updatable=false)이 null인 사본이 관리 상태를 덮어 쓰므로 그 방식은
     * 피한다. 접수 시점 스냅샷은 아예 갱신 대상이 아니다(FR-013c).
     */
    @Override
    public ParamSearch update(ParamSearch paramSearch) {
        if (paramSearch.getId() == null) {
            throw new IllegalArgumentException("update is for persisted searches only: id is null");
        }
        ParamSearchJpaEntity entity =
                jpaRepository
                        .findById(paramSearch.getId())
                        .orElseThrow(
                                () ->
                                        new IllegalArgumentException(
                                                "param search not found: id="
                                                        + paramSearch.getId()));
        entity.applyProgress(
                paramSearch.getStatus(),
                paramSearch.getStopReason(),
                paramSearch.getCounts().toMap(),
                paramSearch.getSelection(),
                paramSearch.getBaselineJobId(),
                paramSearch.isDataVersionChanged(),
                paramSearch.getStartedAt(),
                paramSearch.getFinishedAt());
        return ParamSearchMapper.toDomain(jpaRepository.save(entity));
    }

    @Override
    public Optional<ParamSearch> findByIdForUpdate(Long id) {
        return jpaRepository.findByIdForUpdate(id).map(ParamSearchMapper::toDomain);
    }

    @Override
    public Optional<ParamSearch> findById(Long id) {
        return jpaRepository.findById(id).map(ParamSearchMapper::toDomain);
    }

    @Override
    public Optional<ParamSearch> findBySearchId(String searchId) {
        return jpaRepository.findBySearchId(searchId).map(ParamSearchMapper::toDomain);
    }
}
