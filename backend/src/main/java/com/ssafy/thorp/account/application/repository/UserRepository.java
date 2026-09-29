package com.ssafy.thorp.account.application.repository;

import com.ssafy.thorp.account.domain.model.User;
import java.time.Instant;
import java.util.Optional;

public interface UserRepository {
    User save(User user);

    /**
     * 이메일로 계정을 찾는다.
     *
     * <p><b>정규형 이메일만 넘긴다.</b> 저장된 값은 {@code EmailNormalizer}를 거친 형태이므로, 사용자가 입력한 문자열을 그대로 넘기면 대소문자나
     * 앞뒤 공백 때문에 있는 계정을 못 찾는다 — 로그인에서는 그 실패가 "비밀번호가 틀렸다"와 구분되지 않아 조용히 새어 나간다.
     */
    Optional<User> findByEmail(String email);

    /** 세션에 남은 식별자로 계정을 다시 읽는다. 현재 사용자 조회가 최신 닉네임·상태를 보게 하는 경로다. */
    Optional<User> findById(Long id);

    boolean existsByEmail(String email);

    boolean existsByPhoneNumber(String phoneNumber);

    /**
     * 마지막 로그인 시각만 갱신한다.
     *
     * <p>도메인 객체를 통째로 저장하지 않는다. 로그인은 사용자가 화면에서 프로필을 고치는 것과 다른 경로라, 통째로 저장하면 읽어둔 시점의 옛 닉네임·상태가 함께 나가
     * 상대의 갱신을 되돌린다.
     */
    void updateLastLoginAt(Long id, Instant lastLoginAt);

    /**
     * 닉네임만 갱신한다.
     *
     * <p>{@link #updateLastLoginAt}과 같은 이유로 도메인 객체를 통째로 저장하지 않는다. 다른 기기의 로그인이 같은 행의 마지막 로그인 시각을 쓰는
     * 중이어도 서로의 갱신을 되돌리지 않는다.
     */
    void updateNickname(Long id, String nickname);
}
