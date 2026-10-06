package com.ssafy.thorp.account.application.repository;

import com.ssafy.thorp.account.domain.model.EmailVerification;
import java.util.Optional;

public interface EmailVerificationRepository {

    EmailVerification save(EmailVerification emailVerification);

    /** 해당 이메일의 가장 최근 인증 요청. */
    Optional<EmailVerification> findLatestByEmail(String email);

    /**
     * 이 주소의 발급·대조를 트랜잭션 끝까지 한 줄로 세운다. 호출하는 쪽이 트랜잭션 안에 있어야 한다.
     *
     * <p>행 잠금으로는 안 된다. 재발송은 직전 행을 지우고 새 행을 넣으므로, 그 행을 기다리던 요청은 잠금이 풀린 뒤 행이 사라진 것을 보고 빈 결과를 받는다 —
     * 상한을 통과하거나 "요청 없음"으로 실패한다. 행이 아직 없는 첫 발송도 행 잠금으로는 묶이지 않는다.
     */
    void lockEmail(String email);

    void deleteByEmail(String email);
}
