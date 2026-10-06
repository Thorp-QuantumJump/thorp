package com.ssafy.thorp.account.application;

import com.ssafy.thorp.common.security.SessionUser;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;

/**
 * 세션의 인증 주체에서 사용자 식별자를 꺼낸다.
 *
 * <p>인증 주체는 로그인이 넣어 둔 {@code users.id} 문자열이다. 그 형태가 아니면 우리가 만든 세션이 아니므로 로그인 상태로 보지 않는다. 현재 사용자 조회와
 * 회원정보 수정이 같은 판정을 쓰도록 여기 하나만 둔다.
 *
 * <p><b>인증 종류도 본다.</b> 로그인({@code AuthenticationService#startSession})은 언제나 {@link
 * UsernamePasswordAuthenticationToken}을 넣는다. 소셜 로그인 필터가 잠깐 넣는 {@code OAuth2AuthenticationToken}의
 * 이름은 제공자 사용자 식별자인데 카카오는 숫자라서, 이름만 보면 남의 {@code users.id}로 읽힌다(spec 014 analyze G1). 콜백 처리기가 그
 * token을 바로 바꾸거나 지우지만, 어긋나도 다른 사용자로 보지 않도록 여기서 한 번 더 막는다.
 */
public final class AuthenticatedPrincipal {

    private static final Logger log = LoggerFactory.getLogger(AuthenticatedPrincipal.class);

    private AuthenticatedPrincipal() {}

    public static Optional<Long> userId(Authentication authentication) {
        Optional<Long> userId = SessionUser.idOf(authentication);
        if (userId.isEmpty() && authentication != null && authentication.isAuthenticated()) {
            log.debug("Unusable authentication principal in session");
        }
        return userId;
    }
}
