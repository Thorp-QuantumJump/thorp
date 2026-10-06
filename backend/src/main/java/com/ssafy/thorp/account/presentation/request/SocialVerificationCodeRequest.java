package com.ssafy.thorp.account.presentation.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/** 대기 상태로 보낸 6자리 인증번호. 주소는 받지 않는다 — 대기 상태가 정한다. */
public record SocialVerificationCodeRequest(
        @NotBlank @Pattern(regexp = "^\\d{6}$") String verificationCode) {}
