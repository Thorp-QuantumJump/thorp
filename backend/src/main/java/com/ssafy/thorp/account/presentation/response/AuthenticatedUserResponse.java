package com.ssafy.thorp.account.presentation.response;

import com.ssafy.thorp.account.application.result.AuthenticatedUserResult;

/**
 * 로그인·현재 사용자 조회의 {@code data}.
 *
 * <p>계약(`contracts/auth-api.yaml`의 {@code AuthenticatedUser})이 {@code additionalProperties:
 * false}이므로 네 필드 그대로 유지한다. 전화번호·비밀번호 해시·세션 식별자는 여기 담지 않는다.
 */
public record AuthenticatedUserResponse(Long userId, String email, String nickname, String status) {

    public static AuthenticatedUserResponse from(AuthenticatedUserResult result) {
        return new AuthenticatedUserResponse(
                result.userId(), result.email(), result.nickname(), result.status());
    }
}
