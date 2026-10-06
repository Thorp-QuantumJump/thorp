package com.ssafy.thorp.account.infrastructure.oauth;

import com.ssafy.thorp.account.application.port.PublicSiteUrl;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.stereotype.Component;

/**
 * 제공자 콜백이 인증에 실패했을 때(FR-026).
 *
 * <p>사용자가 제공자 화면에서 취소한 경우({@code access_denied})는 오류가 아니라 취소로 안내한다. state 불일치·재사용, 제공자 오류, token 교환
 * 실패는 모두 같은 일반 안내다 — 원인을 화면에 나눠 보여 줄 이유가 없고, 로그에는 오류 코드만 남긴다(인가 코드는 남기지 않는다).
 */
@Component
public class SocialLoginFailureHandler implements AuthenticationFailureHandler {

    private static final Logger log = LoggerFactory.getLogger(SocialLoginFailureHandler.class);

    static final String ACCESS_DENIED = "access_denied";

    private final PublicSiteUrl publicSiteUrl;

    public SocialLoginFailureHandler(PublicSiteUrl publicSiteUrl) {
        this.publicSiteUrl = publicSiteUrl;
    }

    @Override
    public void onAuthenticationFailure(
            HttpServletRequest request,
            HttpServletResponse response,
            AuthenticationException exception)
            throws IOException {
        boolean linkIntent = SocialLoginSuccessHandler.takeLinkIntent(request);
        String errorCode =
                exception instanceof OAuth2AuthenticationException oauth
                        ? oauth.getError().getErrorCode()
                        : exception.getClass().getSimpleName();
        boolean cancelled = ACCESS_DENIED.equals(errorCode);
        if (!cancelled) {
            log.warn("Social login failed: error={}", errorCode);
        }
        String result = cancelled ? "cancelled" : "error";
        response.sendRedirect(
                publicSiteUrl.of(
                        (linkIntent ? "/auth/social/link?social=" : "/login?social=") + result));
    }
}
