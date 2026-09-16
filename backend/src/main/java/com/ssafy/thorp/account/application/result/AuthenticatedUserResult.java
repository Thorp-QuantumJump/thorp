package com.ssafy.thorp.account.application.result;

/**
 * 인증된 사용자로서 밖에 보여도 되는 값만 담는다. 컨트롤러가 응답 DTO로 변환한다.
 *
 * <p>비밀번호 해시·전화번호·세션 식별자는 여기 담지 않는다. 계약(`contracts/auth-api.yaml`의 {@code AuthenticatedUser})이
 * {@code additionalProperties: false}라서, 여기에 필드를 더하면 그대로 계약 위반이 된다.
 */
public record AuthenticatedUserResult(Long userId, String email, String nickname, String status) {}
