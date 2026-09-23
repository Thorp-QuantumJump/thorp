package com.ssafy.thorp.news.infrastructure.persistence;

import com.ssafy.thorp.news.application.repository.NewsCollectionStateRepository;
import com.ssafy.thorp.news.domain.model.NewsCollectionState;
import com.ssafy.thorp.news.domain.vo.NewsSourceKey;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link NewsCollectionStateRepository} 구현.
 *
 * <p>도메인이 계산한 다음 상태를 그대로 반영한다. 수집 위치를 전진시킬지는 도메인이 이미 판단했고 저장소는 그 판단을 뒤집지 않는다(spec FR-013·FR-022).
 */
@Repository
public class NewsCollectionStateRepositoryImpl implements NewsCollectionStateRepository {

    private final CollectionStateJpaRepository jpaRepository;

    public NewsCollectionStateRepositoryImpl(CollectionStateJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    @Transactional
    public NewsCollectionState save(NewsCollectionState state) {
        Optional<NewsCollectionStateJpaEntity> existing =
                jpaRepository.findById(state.getSourceKey());

        if (existing.isPresent()) {
            NewsCollectionStateJpaEntity entity = existing.get();
            entity.apply(
                    state.getStatus(),
                    state.getCursor(),
                    state.getLastAttemptAt(),
                    state.getLastSuccessAt(),
                    state.getNextAttemptAt(),
                    state.getRetryCount(),
                    state.getErrorCategory(),
                    state.getLeaseOwner(),
                    state.getLeaseUntil());
            return NewsPersistenceMapper.toDomain(jpaRepository.save(entity));
        }

        return NewsPersistenceMapper.toDomain(
                jpaRepository.save(NewsPersistenceMapper.toEntity(state)));
    }

    @Override
    public Optional<NewsCollectionState> findBySourceKey(String sourceKey) {
        return jpaRepository.findById(sourceKey).map(NewsPersistenceMapper::toDomain);
    }

    @Override
    public List<NewsCollectionState> findDue(Instant now) {
        return jpaRepository.findDue(now).stream().map(NewsPersistenceMapper::toDomain).toList();
    }

    @Override
    public List<NewsCollectionState> findAll() {
        return jpaRepository.findAll().stream().map(NewsPersistenceMapper::toDomain).toList();
    }

    @Override
    public Optional<Instant> findLatestSuccessAt(boolean publicOnly) {
        return jpaRepository.findLatestSuccessAt(publicOnly, NewsSourceKey.RSS_PREFIX);
    }
}
