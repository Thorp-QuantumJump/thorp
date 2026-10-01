package com.ssafy.thorp.account.infrastructure.persistence;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface EmailVerificationJpaRepository
        extends JpaRepository<EmailVerificationEntity, Long> {

    Optional<EmailVerificationEntity> findTopByEmailOrderByCreatedAtDesc(String email);

    /** 최신 요청을 잠가 읽는다. 같은 주소의 동시 대조·재발송이 여기서 줄을 선다. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query(
            "select v from EmailVerificationEntity v where v.email = :email"
                    + " order by v.createdAt desc limit 1")
    Optional<EmailVerificationEntity> findLatestForUpdate(@Param("email") String email);

    void deleteByEmail(String email);
}
