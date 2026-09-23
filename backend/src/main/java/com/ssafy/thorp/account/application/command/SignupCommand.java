package com.ssafy.thorp.account.application.command;

/** 회원가입 입력값. 컨트롤러가 요청 DTO에서 만들어 넘긴다. */
public record SignupCommand(String email, String password, String nickname, String phoneNumber) {}
