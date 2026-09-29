package com.ssafy.thorp.account.application.command;

/**
 * 닉네임 변경 입력값. 컨트롤러가 세션의 사용자 식별자와 요청 DTO에서 만들어 넘긴다.
 *
 * <p>{@code nickname}은 요청 DTO가 앞뒤 공백을 떼고 길이 검증까지 마친 값이다.
 */
public record ChangeNicknameCommand(Long userId, String nickname) {}
