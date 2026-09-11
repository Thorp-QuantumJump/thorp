package com.ssafy.thorp.news.infrastructure.persistence;

import com.ssafy.thorp.news.domain.vo.PermissionStatus;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** 매체. 공개 조회는 허가된 매체로 먼저 좁힌다(spec FR-016). */
public interface PublisherJpaRepository extends JpaRepository<NewsPublisherJpaEntity, Long> {

    Optional<NewsPublisherJpaEntity> findByPublisherKey(String publisherKey);

    List<NewsPublisherJpaEntity> findByEnabledTrue();

    List<NewsPublisherJpaEntity> findByPermissionStatus(PermissionStatus permissionStatus);

    boolean existsByPermissionStatus(PermissionStatus permissionStatus);
}
