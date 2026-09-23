package com.ssafy.thorp.account.application.result;

/** 회원가입 결과. 컨트롤러가 응답 DTO로 변환한다. */
public record SignupResult(Long userId, String email, String nickname, String status) {}
