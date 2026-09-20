package com.ssafy.thorp.myrecord.infrastructure.persistence;

import com.ssafy.thorp.regime.infrastructure.persistence.RegimeSearchLogJpaEntity;
import java.time.Instant;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * {@code regime_search_log}에 <b>쓰는</b> 저장소. 006이 소유한다.
 *
 * <p>010의 {@code RegimeSearchLogJpaRepository}는 철회 판정만 하는 읽기 전용이고 그대로 둔다(spec 006 T012). 같은 엔티티를
 * 가리키는 저장소를 006 쪽에 따로 두면 010의 계약을 건드리지 않고 행을 만들 수 있다.
 */
interface RegimeSearchLogJpaWriteRepository extends JpaRepository<RegimeSearchLogJpaEntity, Long> {

    @Query(
            """
            select log.id from RegimeSearchLogJpaEntity log
            where log.ownerUserId = :ownerId
              and log.snapshotKey = :snapshotKey
              and log.entryPath = :entryPath
            """)
    Optional<Long> findIdByOwnerAndKeyAndPath(
            @Param("ownerId") long ownerId,
            @Param("snapshotKey") String snapshotKey,
            @Param("entryPath") String entryPath);

    @Modifying
    @Query(
            """
            update RegimeSearchLogJpaEntity log
            set log.viewedAt = :viewedAt
            where log.id = :id
            """)
    void touchViewedAt(@Param("id") long id, @Param("viewedAt") Instant viewedAt);
}
