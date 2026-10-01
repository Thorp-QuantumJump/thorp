package com.ssafy.thorp.account.application.port;

import com.ssafy.thorp.account.domain.model.PendingSocialLogin;
import java.util.Optional;

/**
 * 현재 요청 세션의 소셜 가입·연결 대기 상태(spec 014 FR-025).
 *
 * <p>구현은 서버 세션 속성이다. 만료 판정은 서비스가 한다 — 저장소는 담긴 값을 그대로 돌려준다.
 */
public interface PendingSocialLoginStore {

    Optional<PendingSocialLogin> find();

    void save(PendingSocialLogin pending);

    void clear();
}
