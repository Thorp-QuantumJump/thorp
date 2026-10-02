package com.ssafy.thorp.account.infrastructure.oauth;

import com.ssafy.thorp.account.application.command.SocialProfile;
import com.ssafy.thorp.account.application.port.PublicSiteUrl;
import com.ssafy.thorp.account.application.result.SocialLoginOutcome;
import com.ssafy.thorp.account.application.service.AuthenticationService;
import com.ssafy.thorp.account.application.service.SocialLoginService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

/**
 * 제공자 콜백이 인증까지 끝난 뒤의 처리(plan Outcome classification).
 *
 * <p><b>세션 인증을 여기서 다시 정한다.</b> 필터는 이 처리기를 부르기 전에 {@link OAuth2AuthenticationToken}을 세션에 저장한다. 그
 * token을 그대로 두면 {@code .authenticated()}만 보는 경로가 가입 대기 중인 사용자도 통과시킨다(spec 014 analyze G1). 그래서 로그인
 * 판정이면 이메일 로그인과 같은 인증으로 바꾸고, 그 밖의 판정은 인증을 지운다.
 *
 * <p>FE로 보내는 주소에는 판정과 제공자 이름만 싣는다. 인가 코드·state는 이 응답에서 끝난다(FR-025). 주소는 상대 경로다 — 브라우저가 보는 서비스 주소
 * 그대로 이어진다.
 */
@Component
public class SocialLoginSuccessHandler implements AuthenticationSuccessHandler {

    private static final Logger log = LoggerFactory.getLogger(SocialLoginSuccessHandler.class);

    private final SocialClientRegistrations registrations;
    private final SocialLoginService socialLoginService;
    private final AuthenticationService authenticationService;

    private final PublicSiteUrl publicSiteUrl;

    public SocialLoginSuccessHandler(
            SocialClientRegistrations registrations,
            SocialLoginService socialLoginService,
            AuthenticationService authenticationService,
            PublicSiteUrl publicSiteUrl) {
        this.registrations = registrations;
        this.socialLoginService = socialLoginService;
        this.authenticationService = authenticationService;
        this.publicSiteUrl = publicSiteUrl;
    }

    @Override
    public void onAuthenticationSuccess(
            HttpServletRequest request, HttpServletResponse response, Authentication authentication)
            throws IOException {
        boolean linkIntent = takeLinkIntent(request);
        // 로그인 판정이 아니어도 꺼내 지운다 — 다음 시작이 이전 자리로 튀지 않게.
        String returnTarget = SocialAuthorizationRequestResolver.takeReturnTarget(request);
        String target;
        try {
            target = handle(request, response, authentication, linkIntent, returnTarget);
        } catch (RuntimeException unexpected) {
            log.error("Social login callback failed", unexpected);
            authenticationService.dropAuthentication(request, response);
            target = linkIntent ? "/auth/social/link?social=error" : "/login?social=error";
        }
        // 서비스 주소 기준 절대 주소로 보낸다. 상대 경로면 Tomcat이 요청 Host로 절대 주소를 만들어 로컬에서 백엔드 포트로 떨어진다.
        response.sendRedirect(publicSiteUrl.of(target));
    }

    private String handle(
            HttpServletRequest request,
            HttpServletResponse response,
            Authentication authentication,
            boolean linkIntent,
            String returnTarget) {
        OAuth2AuthenticationToken token = (OAuth2AuthenticationToken) authentication;
        SocialProviderAdapter adapter =
                registrations
                        .adapter(token.getAuthorizedClientRegistrationId())
                        .orElseThrow(() -> new IllegalStateException("provider not enabled"));
        SocialProfile profile = adapter.extract(token.getPrincipal());

        SocialLoginOutcome outcome = socialLoginService.resolve(profile, linkIntent);

        if (outcome.type() == SocialLoginOutcome.Type.LOGGED_IN
                && authenticationService
                        .startSession(outcome.userId(), request, response)
                        .isPresent()) {
            log.info("Social login: userId={}, provider={}", outcome.userId(), outcome.provider());
            // 경유 화면 없이 시작할 때 적은 자리로 바로 간다(FR-011). FE 는 켜질 때 하는 현재 사용자 확인으로 상태를 맞춘다.
            return returnTarget;
        }

        authenticationService.dropAuthentication(request, response);
        String provider = outcome.provider().registrationId();
        return switch (outcome.type()) {
            case LOGGED_IN, ACCOUNT_UNAVAILABLE -> "/login?social=error";
            case EMAIL_MISSING -> "/auth/social/email-required?provider=" + provider;
            case EMAIL_VERIFICATION -> "/auth/social/verify-email";
            case PROVIDER_CONFLICT -> "/auth/social/already-linked?provider=" + provider;
            case LINK_REQUIRED -> "/auth/social/link";
            case SIGNUP_REQUIRED -> "/signup/social";
            case LINK_FAILED -> "/auth/social/link?social=error";
        };
    }

    /** 시작할 때 남긴 {@code intent=link} 표시를 한 번만 읽고 지운다. */
    static boolean takeLinkIntent(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session == null) {
            return false;
        }
        Object intent = session.getAttribute(SocialAuthorizationRequestResolver.INTENT_ATTRIBUTE);
        session.removeAttribute(SocialAuthorizationRequestResolver.INTENT_ATTRIBUTE);
        return SocialAuthorizationRequestResolver.LINK_INTENT.equals(intent);
    }
}
