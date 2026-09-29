package com.ssafy.thorp.account.presentation.controller;

import com.ssafy.thorp.account.application.AuthenticatedPrincipal;
import com.ssafy.thorp.account.application.command.ChangeNicknameCommand;
import com.ssafy.thorp.account.application.result.AuthenticatedUserResult;
import com.ssafy.thorp.account.application.service.AccountProfileService;
import com.ssafy.thorp.account.presentation.request.ChangeNicknameRequest;
import com.ssafy.thorp.account.presentation.response.AuthenticatedUserResponse;
import com.ssafy.thorp.common.exception.BusinessException;
import com.ssafy.thorp.common.exception.ErrorCode;
import com.ssafy.thorp.common.web.response.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 로그인한 사용자의 회원정보 수정. 비로그인 요청은 {@code SecurityConfig}의 진입점이 401로 끊는다. */
@RestController
@RequestMapping("/accounts/me")
public class AccountProfileController {

    private final AccountProfileService accountProfileService;

    public AccountProfileController(AccountProfileService accountProfileService) {
        this.accountProfileService = accountProfileService;
    }

    /** 닉네임을 바꾼다. 응답은 현재 사용자 조회와 같은 모양이라 FE가 그대로 로그인 상태에 반영한다. */
    @PatchMapping
    public ResponseEntity<ApiResponse<AuthenticatedUserResponse>> changeNickname(
            @Valid @RequestBody ChangeNicknameRequest request, Authentication authentication) {

        Long userId =
                AuthenticatedPrincipal.userId(authentication)
                        .orElseThrow(
                                () -> new BusinessException(ErrorCode.AUTHENTICATION_REQUIRED));

        AuthenticatedUserResult result =
                accountProfileService.changeNickname(
                        new ChangeNicknameCommand(userId, request.getNickname()));

        return ResponseEntity.ok(ApiResponse.ok(AuthenticatedUserResponse.from(result)));
    }
}
