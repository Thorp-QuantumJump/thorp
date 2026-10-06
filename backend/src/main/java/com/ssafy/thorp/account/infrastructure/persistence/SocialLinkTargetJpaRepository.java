package com.ssafy.thorp.account.infrastructure.persistence;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SocialLinkTargetJpaRepository extends JpaRepository<SocialLinkTargetEntity, Long> {

    /** 없으면 빈 기록을 만든다. 동시에 둘이 만들려 해도 하나만 남는다. */
    @Modifying
    @Query(
            value =
                    "insert into social_link_targets (user_id, window_started_at, attempts, codes_sent)"
                            + " values (:userId, :now, 0, 0) on conflict (user_id) do nothing",
            nativeQuery = true)
    int insertIfAbsent(@Param("userId") Long userId, @Param("now") Instant now);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from SocialLinkTargetEntity t where t.userId = :userId")
    Optional<SocialLinkTargetEntity> findForUpdate(@Param("userId") Long userId);

    @Modifying
    @Query("delete from SocialLinkTargetEntity t where t.windowStartedAt < :cutoff")
    int deleteWindowsStartedBefore(@Param("cutoff") Instant cutoff);
}
