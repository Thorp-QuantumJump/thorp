package com.ssafy.thorp.news.infrastructure.persistence;

import com.ssafy.thorp.news.application.repository.NewsPublisherPermissionHistoryRepository;
import com.ssafy.thorp.news.domain.model.NewsPublisherPermissionHistory;
import com.ssafy.thorp.news.domain.vo.PermissionEventType;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link NewsPublisherPermissionHistoryRepository} 구현.
 *
 * <p>이력 추가와 매체 현재 상태 갱신을 한 트랜잭션에서 한다. 이력만 남고 상태가 안 바뀌면 공개 조회가 옛 상태로 계속 답하고, 상태만 바뀌고 이력이 없으면 근거가
 * 사라진다(spec FR-015).
 */
@Repository
public class NewsPublisherPermissionHistoryRepositoryImpl
        implements NewsPublisherPermissionHistoryRepository {

    private final PermissionHistoryJpaRepository historyRepository;
    private final PublisherJpaRepository publisherRepository;

    public NewsPublisherPermissionHistoryRepositoryImpl(
            PermissionHistoryJpaRepository historyRepository,
            PublisherJpaRepository publisherRepository) {
        this.historyRepository = historyRepository;
        this.publisherRepository = publisherRepository;
    }

    @Override
    @Transactional
    public NewsPublisherPermissionHistory appendAndApply(NewsPublisherPermissionHistory event) {
        NewsPublisherJpaEntity publisher =
                publisherRepository
                        .findById(event.publisherId())
                        .orElseThrow(
                                () ->
                                        new IllegalArgumentException(
                                                "등록되지 않은 매체다: " + event.publisherId()));

        NewsPublisherPermissionHistoryJpaEntity saved =
                historyRepository.save(NewsPersistenceMapper.toEntity(event));

        // 요청 사건도 현재 상태를 PENDING으로 되돌린다. 재확인 중인 매체가 공개에 남지 않게 한다.
        publisher.applyPermission(event.permissionStatus(), event.occurredAt());
        publisherRepository.save(publisher);

        return NewsPersistenceMapper.toDomain(saved);
    }

    @Override
    public List<NewsPublisherPermissionHistory> findByPublisherId(Long publisherId) {
        return historyRepository.findByPublisherIdOrderByOccurredAtAscIdAsc(publisherId).stream()
                .map(NewsPersistenceMapper::toDomain)
                .toList();
    }

    @Override
    public Optional<NewsPublisherPermissionHistory> findLatestResponse(Long publisherId) {
        return historyRepository
                .findFirstByPublisherIdAndEventTypeOrderByOccurredAtDescIdDesc(
                        publisherId, PermissionEventType.RESPONDED)
                .map(NewsPersistenceMapper::toDomain);
    }
}
