package com.ssafy.thorp.account.application.repository;

import com.ssafy.thorp.account.domain.model.EmailVerification;
import java.util.Optional;

public interface EmailVerificationRepository {

    EmailVerification save(EmailVerification emailVerification);

    /** 해당 이메일의 가장 최근 인증 요청. */
    Optional<EmailVerification> findLatestByEmail(String email);

    void deleteByEmail(String email);
}
