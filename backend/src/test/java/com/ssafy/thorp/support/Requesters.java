package com.ssafy.thorp.support;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;

import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * 로그인 세션 흉내. 인증 주체는 spec 014 로그인이 세션에 넣는 {@code users.id} 문자열이고({@code
 * AuthenticationService.login}), {@code CurrentRequester}가 그 형태만 요청자로 받는다.
 */
public final class Requesters {

    private Requesters() {}

    public static RequestPostProcessor as(long requesterId) {
        return user(String.valueOf(requesterId));
    }
}
