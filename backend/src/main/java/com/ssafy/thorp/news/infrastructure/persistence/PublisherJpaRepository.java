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

    Optional<NewsPublisherJpaEntity> findByRegisteredDomain(String registeredDomain);

    /**
     * 허가 사건을 반영하는 동안 매체 행을 잠근다.
     *
     * <p>이력 추가와 현재 상태 갱신 사이에 다른 사건이 끼어들면, 늦게 저장한 쪽이 먼저 저장한 쪽의 상태를 덮는다. 두 회신이 같은 순간에 들어오는 일은 드물지만 그때
     * 남는 상태가 어느 쪽인지 알 수 없는 것이 문제다.
     */
    @org.springframework.data.jpa.repository.Lock(
            jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query(
            "SELECT p FROM NewsPublisherJpaEntity p WHERE p.id = :id")
    Optional<NewsPublisherJpaEntity> findByIdForUpdate(
            @org.springframework.data.repository.query.Param("id") Long id);
}
