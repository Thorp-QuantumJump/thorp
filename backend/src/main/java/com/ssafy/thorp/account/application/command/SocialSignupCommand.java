package com.ssafy.thorp.account.application.command;

/** 소셜 신규 가입의 추가 정보(FR-023). 이메일은 대기 상태의 확인된 값을 쓰고 비밀번호는 받지 않는다. */
public record SocialSignupCommand(String nickname, String phoneNumber) {}
