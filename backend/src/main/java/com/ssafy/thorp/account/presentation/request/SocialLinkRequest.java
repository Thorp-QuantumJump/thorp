package com.ssafy.thorp.account.presentation.request;

import com.ssafy.thorp.account.application.command.SocialLinkCommand;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/**
 * 기존 계정 소유 확인(FR-024). {@code method}에 맞는 값만 필수다 — 맞지 않는 값이 비어 있으면 서비스가 거절한다.
 *
 * <p>비밀번호에는 가입 길이 규칙을 걸지 않는다. 로그인 요청과 같은 이유다.
 */
public record SocialLinkRequest(
        @NotNull SocialLinkCommand.Method method,
        String password,
        @Pattern(regexp = "^\\d{6}$") String verificationCode) {}
