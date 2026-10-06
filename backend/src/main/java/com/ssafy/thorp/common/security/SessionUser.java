package com.ssafy.thorp.common.security;

import java.util.Optional;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;

/**
 * 세션의 인증에서 로그인한 사용자 식별자({@code users.id})를 꺼내는 하나뿐인 규칙.
 *
 * <p>로그인(spec 014 {@code AuthenticationService#startSession})은 언제나 이름이 {@code users.id}인 {@link
 * UsernamePasswordAuthenticationToken}을 넣는다. <b>인증 종류도 본다.</b> 소셜 로그인 필터가 잠깐 넣는 {@code
 * OAuth2AuthenticationToken}의 이름은 제공자 사용자 식별자인데 카카오는 숫자라서, 이름만 보면 남의 {@code users.id}로 읽힌다(spec 014
 * analyze G1). 계정·백테스트 요청자·배우기 관리가 각자 이 판정을 베끼면 다음에 인증 종류가 늘 때 한 곳이 빠진다.
 */
public final class SessionUser {

    private SessionUser() {}

    public static Optional<Long> idOf(Authentication authentication) {
        if (!(authentication instanceof UsernamePasswordAuthenticationToken)
                || !authentication.isAuthenticated()) {
            return Optional.empty();
        }
        try {
            return Optional.of(Long.valueOf(authentication.getName()));
        } catch (NumberFormatException notOurSession) {
            return Optional.empty();
        }
    }
}
