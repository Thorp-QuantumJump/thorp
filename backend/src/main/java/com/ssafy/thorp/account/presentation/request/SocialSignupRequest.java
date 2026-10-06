package com.ssafy.thorp.account.presentation.request;

import com.ssafy.thorp.account.domain.model.User;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 소셜 신규 가입의 추가 정보(FR-023). 기준은 이메일 가입과 같다(FR-001). 이메일·비밀번호 필드는 없다.
 *
 * @param nickname 검증 전에 앞뒤 공백을 뗀다. 회원정보 수정과 같은 이유다.
 * @param phoneNumber 선택 항목(spec 014 2026-10-02). 화면은 보내지 않는다. 실려 오면 형식을 검사한다 — {@code @Pattern}은
 *     {@code null}을 통과시킨다.
 */
public record SocialSignupRequest(
        @NotBlank @Size(min = 2, max = 20) String nickname,
        @Pattern(regexp = User.PHONE_NUMBER_REGEX, message = User.PHONE_NUMBER_MESSAGE)
                String phoneNumber) {

    public SocialSignupRequest {
        nickname = nickname == null ? null : nickname.strip();
    }
}
