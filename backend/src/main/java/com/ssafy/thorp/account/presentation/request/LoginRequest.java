package com.ssafy.thorp.account.presentation.request;

import com.fasterxml.jackson.annotation.JsonSetter;
import com.ssafy.thorp.account.application.EmailNormalizer;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

/**
 * 로그인 요청.
 *
 * <p>비밀번호에는 가입 규칙(8~25자)을 걸지 않는다. 걸면 규칙이 바뀌기 전에 가입한 계정이 자기 비밀번호로 로그인하지 못하고, 거절 응답의 종류가 달라져 "이 길이
 * 규칙에 맞는 계정이 있다"는 힌트가 된다.
 */
public class LoginRequest {

    @NotBlank @Email private String email;

    @NotBlank private String password;

    public String getEmail() {
        return email;
    }

    /** 검증 전에 정규화한다. 검증 뒤에 하면 앞뒤 공백이 붙은 이메일이 형식 오류로 먼저 걸린다. */
    @JsonSetter
    public void setEmail(String email) {
        this.email = email == null ? null : EmailNormalizer.normalize(email);
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }
}
