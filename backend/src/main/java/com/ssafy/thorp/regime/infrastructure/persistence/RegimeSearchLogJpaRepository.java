package com.ssafy.thorp.regime.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface RegimeSearchLogJpaRepository extends JpaRepository<RegimeSearchLogJpaEntity, Long> {

    /** 그 키를 가리키던 기록이 있는데 참조가 끊겼는가 — 철회의 흔적이다(data-model §5·§7). */
    @Query(
            """
            select count(log) > 0 from RegimeSearchLogJpaEntity log
            where log.snapshotKey = :snapshotKey
              and log.snapshotId is null
            """)
    boolean existsWithdrawn(@Param("snapshotKey") String snapshotKey);
}
