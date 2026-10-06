package com.ssafy.thorp.account.application.command;

/**
 * 기존 계정 소유 확인(FR-024). {@link Method}에 맞는 값만 쓴다.
 *
 * <p>이미 연결된 다른 소셜 계정으로 확인하는 경우는 여기로 오지 않는다 — 제공자 로그인을 한 번 더 거치는 {@code intent=link} 경로가 맡는다.
 */
public record SocialLinkCommand(Method method, String password, String verificationCode) {

    public enum Method {
        PASSWORD,
        EMAIL_CODE,
        VERIFIED_EMAIL
    }
}
