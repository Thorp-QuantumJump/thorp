package com.ssafy.thorp.account.application.result;

import com.ssafy.thorp.account.domain.model.SocialProvider;

/**
 * 제공자 콜백의 판정(plan Outcome classification). 콜백 처리기가 이 값으로 세션을 정하고 FE 경로로 보낸다.
 *
 * @param userId {@link Type#LOGGED_IN}일 때만 있다.
 */
public record SocialLoginOutcome(Type type, SocialProvider provider, Long userId) {

    public enum Type {
        LOGGED_IN,
        ACCOUNT_UNAVAILABLE,
        EMAIL_MISSING,
        EMAIL_VERIFICATION,
        PROVIDER_CONFLICT,
        LINK_REQUIRED,
        SIGNUP_REQUIRED,
        /** {@code intent=link}로 들어왔지만 그 소셜 계정이 연결 대상 계정의 것이 아니다. 대기 상태는 그대로 둔다. */
        LINK_FAILED
    }

    public static SocialLoginOutcome of(Type type, SocialProvider provider) {
        return new SocialLoginOutcome(type, provider, null);
    }

    public static SocialLoginOutcome loggedIn(SocialProvider provider, Long userId) {
        return new SocialLoginOutcome(Type.LOGGED_IN, provider, userId);
    }
}
