package com.ssafy.thorp.account.application.repository;

import com.ssafy.thorp.account.domain.model.EmailVerification;
import java.util.Optional;

public interface EmailVerificationRepository {

    EmailVerification save(EmailVerification emailVerification);

    /** 해당 이메일의 가장 최근 인증 요청. */
    Optional<EmailVerification> findLatestByEmail(String email);

    /** 최신 요청을 잠가 읽는다. 호출하는 쪽이 트랜잭션 안에 있어야 한다. */
    Optional<EmailVerification> findLatestByEmailForUpdate(String email);

    void deleteByEmail(String email);
}
