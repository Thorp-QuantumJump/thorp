package com.ssafy.thorp.regimelink.infrastructure.persistence;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

interface RegimeLinkFilterJpaRepository extends JpaRepository<RegimeLinkFilterJpaEntity, Long> {

    Optional<RegimeLinkFilterJpaEntity> findByFilterKey(String filterKey);
}
