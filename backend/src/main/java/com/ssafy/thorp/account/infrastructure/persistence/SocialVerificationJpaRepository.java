package com.ssafy.thorp.account.infrastructure.persistence;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SocialVerificationJpaRepository
        extends JpaRepository<SocialVerificationEntity, String> {

    /** 없으면 빈 기록을 만든다. 동시에 둘이 만들려 해도 하나만 남는다. */
    @Modifying
    @Query(
            value =
                    "insert into social_pending_verifications"
                            + " (id, code_attempts, codes_sent, password_attempts, created_at)"
                            + " values (:id, 0, 0, 0, :now) on conflict (id) do nothing",
            nativeQuery = true)
    int insertIfAbsent(@Param("id") String id, @Param("now") Instant now);

    @Modifying
    @Query("delete from SocialVerificationEntity v where v.createdAt < :cutoff")
    int deleteCreatedBefore(@Param("cutoff") Instant cutoff);

    /** 행을 잠근다. 같은 기록을 바꾸려는 요청은 여기서 줄을 선다. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select v from SocialVerificationEntity v where v.id = :id")
    Optional<SocialVerificationEntity> findForUpdate(@Param("id") String id);
}
