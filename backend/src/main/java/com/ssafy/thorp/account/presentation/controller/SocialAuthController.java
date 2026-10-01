package com.ssafy.thorp.account.presentation.controller;

import com.ssafy.thorp.account.application.AuthenticatedPrincipal;
import com.ssafy.thorp.account.application.command.SocialLinkCommand;
import com.ssafy.thorp.account.application.command.SocialSignupCommand;
import com.ssafy.thorp.account.application.port.SocialProviderCatalog;
import com.ssafy.thorp.account.application.result.AuthenticatedUserResult;
import com.ssafy.thorp.account.application.service.PendingSocialLoginService;
import com.ssafy.thorp.account.application.service.SocialLinkService;
import com.ssafy.thorp.account.application.service.SocialSignupService;
import com.ssafy.thorp.account.domain.model.SocialProvider;
import com.ssafy.thorp.account.presentation.request.SocialLinkRequest;
import com.ssafy.thorp.account.presentation.request.SocialSignupRequest;
import com.ssafy.thorp.account.presentation.request.SocialVerificationCodeRequest;
import com.ssafy.thorp.account.presentation.response.AuthenticatedUserResponse;
import com.ssafy.thorp.account.presentation.response.PendingSocialResponse;
import com.ssafy.thorp.account.presentation.response.SocialProvidersResponse;
import com.ssafy.thorp.common.exception.BusinessException;
import com.ssafy.thorp.common.exception.ErrorCode;
import com.ssafy.thorp.common.web.response.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.io.IOException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 소셜 로그인 API(spec 014 US6·US7). 계약은 {@code contracts/auth-api.yaml}의 소셜 operation이다.
 *
 * <p>제공자 인가 시작과 콜백은 Spring Security 필터가 처리한다. 여기 있는 것은 콜백 뒤 FE 화면이 쓰는 JSON API와, 필터가 인가 요청을 만들지 않은
 * 시작 요청의 뒤처리다.
 */
@RestController
@RequestMapping("/auth")
public class SocialAuthController {

    private final SocialProviderCatalog providerCatalog;
    private final PendingSocialLoginService pendingService;
    private final SocialSignupService signupService;
    private final SocialLinkService linkService;

    public SocialAuthController(
            SocialProviderCatalog providerCatalog,
            PendingSocialLoginService pendingService,
            SocialSignupService signupService,
            SocialLinkService linkService) {
        this.providerCatalog = providerCatalog;
        this.pendingService = pendingService;
        this.signupService = signupService;
        this.linkService = linkService;
    }

    @GetMapping("/social/providers")
    public ResponseEntity<ApiResponse<SocialProvidersResponse>> providers() {
        return ResponseEntity.ok(
                ApiResponse.ok(
                        new SocialProvidersResponse(
                                providerCatalog.enabledProviders().stream()
                                        .map(SocialProvider::registrationId)
                                        .toList())));
    }

    /**
     * 필터가 인가 요청을 만들지 않은 시작 요청. 이미 로그인했으면 {@code /home}으로 보내고(FR-019), 켜지지 않은 제공자면 404다.
     *
     * <p>켜진 제공자이면서 비로그인이면 필터가 먼저 제공자로 보내므로 여기 닿지 않는다.
     */
    @GetMapping("/oauth2/authorization/{provider}")
    public void startNotHandled(
            @PathVariable String provider,
            Authentication authentication,
            HttpServletResponse response)
            throws IOException {
        if (AuthenticatedPrincipal.userId(authentication).isPresent()) {
            response.sendRedirect("/home");
            return;
        }
        throw new BusinessException(ErrorCode.SOCIAL_PROVIDER_NOT_ENABLED);
    }

    @GetMapping("/social/pending")
    public ResponseEntity<ApiResponse<PendingSocialResponse>> pending() {
        return ResponseEntity.ok(ApiResponse.ok(PendingSocialResponse.from(pendingService.view())));
    }

    @DeleteMapping("/social/pending")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void cancel() {
        pendingService.cancel();
    }

    @PostMapping("/social/pending/email-verifications")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void sendCode() {
        pendingService.sendCode();
    }

    @PostMapping("/social/pending/email-verifications/confirm")
    public ResponseEntity<ApiResponse<PendingSocialResponse>> confirmProviderEmail(
            @Valid @RequestBody SocialVerificationCodeRequest request) {
        return ResponseEntity.ok(
                ApiResponse.ok(
                        PendingSocialResponse.from(
                                pendingService.confirmProviderEmail(request.verificationCode()))));
    }

    @PostMapping("/social/signup")
    public ResponseEntity<ApiResponse<AuthenticatedUserResponse>> signup(
            @Valid @RequestBody SocialSignupRequest request,
            HttpServletRequest httpRequest,
            HttpServletResponse httpResponse) {
        AuthenticatedUserResult result =
                signupService.signup(
                        new SocialSignupCommand(request.getNickname(), request.getPhoneNumber()),
                        httpRequest,
                        httpResponse);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(AuthenticatedUserResponse.from(result)));
    }

    @PostMapping("/social/link")
    public ResponseEntity<ApiResponse<AuthenticatedUserResponse>> link(
            @Valid @RequestBody SocialLinkRequest request,
            HttpServletRequest httpRequest,
            HttpServletResponse httpResponse) {
        AuthenticatedUserResult result =
                linkService.link(
                        new SocialLinkCommand(
                                request.method(), request.password(), request.verificationCode()),
                        httpRequest,
                        httpResponse);
        return ResponseEntity.ok(ApiResponse.ok(AuthenticatedUserResponse.from(result)));
    }
}
