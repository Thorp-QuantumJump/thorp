package com.ssafy.thorp.account.application.command;

/**
 * 로그인 입력값. 컨트롤러가 요청 DTO에서 만들어 넘긴다.
 *
 * <p>{@code email}은 정규화 전 값이어도 된다 — 서비스가 조회 직전에 다시 정규화한다. 비밀번호는 이 record 밖으로 복제하지 않는다.
 */
public record LoginCommand(String email, String password) {}
