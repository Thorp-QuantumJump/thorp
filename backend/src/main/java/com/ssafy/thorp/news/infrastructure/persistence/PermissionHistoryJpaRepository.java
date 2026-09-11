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
}
