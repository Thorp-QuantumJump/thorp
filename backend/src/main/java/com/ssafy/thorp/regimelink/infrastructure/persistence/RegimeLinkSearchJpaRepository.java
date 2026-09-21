package com.ssafy.thorp.regimelink.infrastructure.persistence;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

interface RegimeLinkSearchJpaRepository extends JpaRepository<RegimeLinkSearchJpaEntity, Long> {

    Optional<RegimeLinkSearchJpaEntity> findBySearchId(String searchId);

    Optional<RegimeLinkSearchJpaEntity> findByOwnerUserIdAndClientRequestId(
            Long ownerUserId, String clientRequestId);
}
