package com.ssafy.thorp.regimelink.infrastructure.persistence;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

interface RegimeLinkJpaRepository extends JpaRepository<RegimeLinkJpaEntity, Long> {

    Optional<RegimeLinkJpaEntity> findByLinkId(String linkId);

    Optional<RegimeLinkJpaEntity> findByRunIdAndSnapshotKeyAndEndMonthAndScenario(
            Long runId, String snapshotKey, LocalDate endMonth, String scenario);

    List<RegimeLinkJpaEntity> findByRunId(Long runId);
}
