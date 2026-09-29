package com.ssafy.thorp.account.application;

import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;

/**
 * 세션의 인증 주체에서 사용자 식별자를 꺼낸다.
 *
 * <p>인증 주체는 로그인이 넣어 둔 {@code users.id} 문자열이다. 그 형태가 아니면 우리가 만든 세션이 아니므로 로그인 상태로 보지 않는다. 현재 사용자 조회와
 * 회원정보 수정이 같은 판정을 쓰도록 여기 하나만 둔다.
 */
public final class AuthenticatedPrincipal {

    private static final Logger log = LoggerFactory.getLogger(AuthenticatedPrincipal.class);

    private AuthenticatedPrincipal() {}

    public static Optional<Long> userId(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            return Optional.empty();
        }
        try {
            return Optional.of(Long.valueOf(authentication.getName()));
        } catch (NumberFormatException e) {
            log.warn("Unusable authentication principal in session");
            return Optional.empty();
        }
    }
}
