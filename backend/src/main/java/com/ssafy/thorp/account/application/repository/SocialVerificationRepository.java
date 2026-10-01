package com.ssafy.thorp.account.application.repository;

import com.ssafy.thorp.account.domain.model.SocialVerification;
import java.time.Instant;

/**
 * 소셜 대기 상태의 인증번호·시도 기록 저장소.
 *
 * <p>{@link #lockOrOpen}은 행을 잠근다. 같은 대기 상태로 동시에 들어온 요청은 여기서 줄을 서므로 시도 횟수를 함께 넘기지 못한다.
 */
public interface SocialVerificationRepository {

    /** 기록을 잠가 읽는다. 없으면 빈 기록을 만들어 잠근다. 호출하는 쪽이 트랜잭션 안에 있어야 한다. */
    SocialVerification lockOrOpen(String id, Instant now);

    void save(SocialVerification verification);

    void delete(String id);
}
