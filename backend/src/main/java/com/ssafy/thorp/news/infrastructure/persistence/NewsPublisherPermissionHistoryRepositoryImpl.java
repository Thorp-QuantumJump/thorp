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
        // 이력 추가와 상태 갱신 사이에 다른 사건이 끼어들지 않도록 매체 행을 잠근다.
        NewsPublisherJpaEntity publisher =
                publisherRepository
                        .findByIdForUpdate(event.publisherId())
                        .orElseThrow(
                                () ->
                                        new IllegalArgumentException(
                                                "등록되지 않은 매체다: " + event.publisherId()));

        boolean latest = isLatest(event);

        NewsPublisherPermissionHistoryJpaEntity saved =
                historyRepository.save(NewsPersistenceMapper.toEntity(event));

        if (latest) {
            // 요청 사건도 현재 상태를 PENDING으로 되돌린다. 재확인 중인 매체가 공개에 남지 않게 한다.
            publisher.applyPermission(event.permissionStatus(), event.occurredAt());
            publisherRepository.save(publisher);
        }

        return NewsPersistenceMapper.toDomain(saved);
    }

    /**
     * 이 사건이 그 매체의 가장 최근 사건인지.
     *
     * <p><b>이력은 늘 남기되 현재 상태는 가장 늦은 사건만 반영한다.</b> 회신을 늦게 입력하는 일은 정상이다 — 메일함에 묻혀 있던 답장을 며칠 뒤에 넣을 수
     * 있다. 그때 그 과거 회신이 현재 상태를 덮으면, 이미 받아 둔 최신 회신이 조용히 무효가 되고 공개 여부가 뒤집힌다.
     *
     * <p>같은 순간이면 새 사건을 최신으로 본다. 같은 시각에 두 회신이 들어오는 일은 사실상 없고, 있다면 나중에 기록한 쪽이 사람이 마지막으로 판단한 것이기 때문이다.
     */
    private boolean isLatest(NewsPublisherPermissionHistory event) {
        return historyRepository
                .findFirstByPublisherIdOrderByOccurredAtDescIdDesc(event.publisherId())
                .map(last -> !event.occurredAt().isBefore(last.getOccurredAt()))
                .orElse(true);
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
