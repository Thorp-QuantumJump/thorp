package com.ssafy.thorp.regime.infrastructure.persistence;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

interface RegimeCaseJpaRepository extends JpaRepository<RegimeCaseJpaEntity, Long> {

    List<RegimeCaseJpaEntity> findBySnapshotIdOrderByRankAsc(Long snapshotId);
}
