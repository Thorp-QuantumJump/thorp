package com.ssafy.thorp.account.presentation.request;

import com.fasterxml.jackson.annotation.JsonSetter;
import com.ssafy.thorp.account.application.EmailNormalizer;
import com.ssafy.thorp.account.domain.model.User;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public class SignupRequest {

    @NotBlank @Email private String email;

    @NotBlank
    @Size(min = 8, max = 25)
    private String password;

    @NotBlank
    @Size(min = 2, max = 20)
    private String nickname;

    /**
     * 국내 휴대폰 번호. 하이픈 없는 숫자만 받는다.
     *
     * <p>국제 표기(+82…)는 받지 않는다. 같은 번호가 두 표기로 각각 저장돼 UNIQUE 제약과 중복 검사를 우회하는 것을 막기 위해서다. 해외 번호가 필요해지면
     * 저장 전 정규화를 함께 도입한다.
     *
     * <p><b>선택 항목이다</b>(spec 014 2026-10-02). 화면은 묻지 않는다. 문자 인증과 함께 다시 받을 때를 위해 형식 검사는 남긴다 —
     * {@code @Pattern}은 {@code null}을 통과시킨다.
     */
    @Pattern(regexp = User.PHONE_NUMBER_REGEX, message = User.PHONE_NUMBER_MESSAGE)
    private String phoneNumber;

    public String getEmail() {
        return email;
    }

    @JsonSetter
    public void setEmail(String email) {
        this.email = email == null ? null : EmailNormalizer.normalize(email);
    }

    public String getPassword() {
        return password;
    }

    public String getNickname() {
        return nickname;
    }

    public String getPhoneNumber() {
        return phoneNumber;
    }
}
