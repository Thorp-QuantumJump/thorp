package com.ssafy.thorp.account.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface EmailVerificationJpaRepository
    extends JpaRepository<EmailVerificationEntity, Long> {

    Optional<EmailVerificationEntity> findTopByEmailOrderByCreatedAtDesc(String email);

    void deleteByEmail(String email);
}
