package com.ssafy.thorp.account.infrastructure.oauth;

import com.ssafy.thorp.account.application.AuthenticatedPrincipal;
import com.ssafy.thorp.account.application.port.PublicSiteUrl;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 이미 로그인한 세션으로 돌아온 제공자 콜백은 처리하지 않고 {@code /home}으로 보낸다(FR-019).
 *
 * <p>시작 요청은 로그인한 사용자를 이미 막지만, 제공자 화면을 다녀오는 사이에 다른 탭에서 이메일로 로그인할 수 있다. 그 콜백을 그대로 처리하면 소셜 로그인 필터가 세션의
 * 로그인을 제공자 인증으로 덮어쓰고, 로그인이 아닌 판정에서 성공 처리기가 그 인증을 지워 사용자가 조용히 로그아웃된다(2026-10-01 code review). 그래서 소셜
 * 로그인 필터보다 앞에서 끊는다. 시작 때 세션에 남은 인가 요청은 다음 시작이 덮어쓴다.
 */
class SignedInCallbackFilter extends OncePerRequestFilter {

    private final PathPatternRequestMatcher callback =
            PathPatternRequestMatcher.withDefaults().matcher("/auth/oauth2/callback/{provider}");
    private final PublicSiteUrl publicSiteUrl;

    SignedInCallbackFilter(PublicSiteUrl publicSiteUrl) {
        this.publicSiteUrl = publicSiteUrl;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !callback.matches(request);
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (AuthenticatedPrincipal.userId(SecurityContextHolder.getContext().getAuthentication())
                .isPresent()) {
            SocialLoginSuccessHandler.takeLinkIntent(request);
            response.sendRedirect(publicSiteUrl.of("/home"));
            return;
        }
        chain.doFilter(request, response);
    }
}
