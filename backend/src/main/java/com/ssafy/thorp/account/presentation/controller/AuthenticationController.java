package com.ssafy.thorp.account.presentation.controller;

import com.ssafy.thorp.account.application.command.LoginCommand;
import com.ssafy.thorp.account.application.result.AuthenticatedUserResult;
import com.ssafy.thorp.account.application.service.AuthenticationService;
import com.ssafy.thorp.account.presentation.request.LoginRequest;
import com.ssafy.thorp.account.presentation.response.AuthenticatedUserResponse;
import com.ssafy.thorp.common.web.response.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** 인증 API. 외부 경로는 프록시가 {@code /api}를 떼고 넘긴 {@code /auth/*}다. */
@RestController
@RequestMapping("/auth")
public class AuthenticationController {

    private final AuthenticationService authenticationService;

    public AuthenticationController(AuthenticationService authenticationService) {
        this.authenticationService = authenticationService;
    }

    /**
     * SPA가 상태 변경 요청에 쓸 CSRF 토큰 쿠키를 준비한다.
     *
     * <p>토큰은 지연 생성이라 {@link CsrfToken#getToken()}을 실제로 불러야 쿠키가 나간다. 값을 응답 본문에 싣지 않는다 — 쿠키로 내려주고
     * 브라우저가 헤더로 되돌려 보내는 것이 이 방식의 전부다.
     */
    @GetMapping("/csrf")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void prepareCsrfToken(CsrfToken csrfToken) {
        csrfToken.getToken();
    }

    @PostMapping("/sessions")
    public ResponseEntity<ApiResponse<AuthenticatedUserResponse>> login(
            @Valid @RequestBody LoginRequest request,
            HttpServletRequest httpRequest,
            HttpServletResponse httpResponse) {

        LoginCommand command = new LoginCommand(request.getEmail(), request.getPassword());

        AuthenticatedUserResult result =
                authenticationService.login(command, httpRequest, httpResponse);

        return ResponseEntity.ok(ApiResponse.ok(AuthenticatedUserResponse.from(result)));
    }

    /**
     * 현재 세션을 끝낸다. 결과는 언제나 204다.
     *
     * <p>본문이 없는 이유는 돌려줄 것이 없어서다 — 로그아웃 뒤의 사용자 정보는 존재하지 않고, 실패 사유를 알려 줄 실패도 없다. FE는 이 204 하나로 '로그아웃
     * 완료'를 판정한다(FR-009). 이미 비로그인이어도 204이므로 재시도가 안전하다.
     */
    @DeleteMapping("/sessions/current")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(HttpServletRequest httpRequest, HttpServletResponse httpResponse) {
        authenticationService.logout(httpRequest, httpResponse);
    }

    /** 비로그인 요청은 여기 닿기 전에 {@code SecurityConfig}의 진입점이 401로 끊는다. */
    @GetMapping("/me")
    public ResponseEntity<ApiResponse<AuthenticatedUserResponse>> currentUser(
            Authentication authentication) {

        AuthenticatedUserResult result = authenticationService.currentUser(authentication);

        return ResponseEntity.ok(ApiResponse.ok(AuthenticatedUserResponse.from(result)));
    }
}
