package com.ssafy.thorp.account.presentation.request;

import com.fasterxml.jackson.annotation.JsonSetter;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** 소셜 신규 가입의 추가 정보(FR-023). 기준은 이메일 가입과 같다(FR-001). 이메일·비밀번호 필드는 없다. */
public class SocialSignupRequest {

    @NotBlank
    @Size(min = 2, max = 20)
    private String nickname;

    /**
     * 선택 항목(spec 014 2026-10-02). 화면은 보내지 않는다. 실려 오면 형식을 검사한다 — {@code @Pattern}은 {@code null}을
     * 통과시킨다.
     */
    @Pattern(regexp = "^01[016789]\\d{7,8}$", message = "국내 휴대폰 번호 형식이어야 합니다. 예: 01012345678")
    private String phoneNumber;

    public String getNickname() {
        return nickname;
    }

    /** 검증 전에 앞뒤 공백을 뗀다. 회원정보 수정과 같은 이유다. */
    @JsonSetter
    public void setNickname(String nickname) {
        this.nickname = nickname == null ? null : nickname.strip();
    }

    public String getPhoneNumber() {
        return phoneNumber;
    }

    public void setPhoneNumber(String phoneNumber) {
        this.phoneNumber = phoneNumber;
    }
}
