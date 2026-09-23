package com.ssafy.thorp.news.infrastructure.persistence;

import com.ssafy.thorp.news.domain.vo.PermissionEventType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** 허가 이력. append-only라 삭제·수정 메서드를 두지 않는다(spec FR-015). */
public interface PermissionHistoryJpaRepository
        extends JpaRepository<NewsPublisherPermissionHistoryJpaEntity, Long> {

    List<NewsPublisherPermissionHistoryJpaEntity> findByPublisherIdOrderByOccurredAtAscIdAsc(
            Long publisherId);

    Optional<NewsPublisherPermissionHistoryJpaEntity>
            findFirstByPublisherIdAndEventTypeOrderByOccurredAtDescIdDesc(
                    Long publisherId, PermissionEventType eventType);

    /** 종류를 가리지 않은 가장 최근 사건. 현재 상태를 늦게 입력된 과거 사건이 덮지 않게 할 때 쓴다. */
    Optional<NewsPublisherPermissionHistoryJpaEntity>
            findFirstByPublisherIdOrderByOccurredAtDescIdDesc(Long publisherId);
}
