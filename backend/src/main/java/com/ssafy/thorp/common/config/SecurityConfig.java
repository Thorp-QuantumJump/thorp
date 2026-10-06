package com.ssafy.thorp.common.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ssafy.thorp.common.exception.ErrorCode;
import com.ssafy.thorp.common.web.response.ApiResponse;
import com.ssafy.thorp.common.web.response.ErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.function.Supplier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.session.ChangeSessionIdAuthenticationStrategy;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.DelegatingSecurityContextRepository;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.RequestAttributeSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfException;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.csrf.CsrfTokenRequestHandler;
import org.springframework.security.web.csrf.XorCsrfTokenRequestAttributeHandler;

/** 서버 세션 인증과 브라우저 요청 보안의 공통 경계를 정의한다. */
@Configuration
public class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            ObjectMapper objectMapper,
            CsrfTokenRepository csrfTokenRepository,
            SecurityContextRepository securityContextRepository,
            SessionAuthenticationStrategy sessionAuthenticationStrategy,
            List<HttpSecurityContributor> contributors)
            throws Exception {
        // 도메인이 더하는 설정(소셜 로그인 등)을 먼저 붙인다. 아래 공통 설정이 같은 항목을 다시 정하면 공통 쪽이 이긴다.
        for (HttpSecurityContributor contributor : contributors) {
            contributor.contribute(http);
        }
        return http.csrf(
                        csrf ->
                                csrf.csrfTokenRepository(csrfTokenRepository)
                                        .csrfTokenRequestHandler(new SpaCsrfTokenRequestHandler()))
                // 로그아웃(DELETE /auth/sessions/current)은 인증을 요구하지 않는다. 비로그인 요청까지 401로 끊으면
                // FE는 세션이 정말 끝났는지 확인하지 못한 채 '로그아웃 미확인'에 머문다(FR-009). CSRF 검사는 그대로 받는다.
                .authorizeHttpRequests(
                        authorize ->
                                authorize
                                        .requestMatchers("/auth/me")
                                        .authenticated()
                                        // 회원정보 수정. 가입(POST /accounts)과 이메일 인증은 비로그인 경로라
                                        // /accounts/** 전체가 아니라 이 경로만 로그인을 요구한다.
                                        .requestMatchers("/accounts/me")
                                        .authenticated()
                                        // 배우기 관리(spec 013). 비로그인은 여기서 401이고, 관리 권한은
                                        // LearningAdminAccess가 판정한다(권한이 없으면 404).
                                        .requestMatchers("/admin/learning/**")
                                        .authenticated()
                                        // 내 기록(spec 006). 경로 전체가 로그인 필요다 — 기록은 사용자 단위로만
                                        // 존재하고 공개 대응물이 없다(FR-020). 남의 기록은 403이 아니라 404로
                                        // 응답해 존재 자체를 알리지 않으며, 그 판정은 서비스가 한다.
                                        .requestMatchers("/my-records/**")
                                        .authenticated()
                                        .anyRequest()
                                        .permitAll())
                .securityContext(
                        context ->
                                context.securityContextRepository(securityContextRepository)
                                        .requireExplicitSave(true))
                .sessionManagement(
                        session ->
                                session.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED)
                                        .sessionAuthenticationStrategy(
                                                sessionAuthenticationStrategy))
                .exceptionHandling(
                        exceptions ->
                                exceptions
                                        .authenticationEntryPoint(
                                                (request, response, exception) ->
                                                        writeError(
                                                                objectMapper,
                                                                response,
                                                                ErrorCode.AUTHENTICATION_REQUIRED))
                                        .accessDeniedHandler(
                                                (request, response, exception) ->
                                                        handleAccessDenied(
                                                                objectMapper, response, exception)))
                .requestCache(cache -> cache.disable())
                .formLogin(form -> form.disable())
                .httpBasic(basic -> basic.disable())
                .logout(logout -> logout.disable())
                .cors(Customizer.withDefaults())
                .build();
    }

    @Bean
    SecurityContextRepository securityContextRepository() {
        return new DelegatingSecurityContextRepository(
                new RequestAttributeSecurityContextRepository(),
                new HttpSessionSecurityContextRepository());
    }

    @Bean
    SessionAuthenticationStrategy sessionAuthenticationStrategy() {
        return new ChangeSessionIdAuthenticationStrategy();
    }

    /**
     * CSRF 토큰 저장소. 토큰은 세션이 아니라 쿠키에 있다.
     *
     * <p>그래서 세션이 바뀌어도 토큰은 그대로 살아남는다 — 로그인·로그아웃에서 값을 갈아 주는 일은 {@code AuthenticationService}가 맡는다. 이
     * 저장소를 빈으로 두는 이유가 그것이다.
     *
     * <p>{@code Secure}는 지정하지 않는다. {@link CookieCsrfTokenRepository}는 값이 없으면 요청이 HTTPS인지 보고 정한다.
     * 운영은 nginx가 TLS를 끝내고 평문으로 넘기므로 {@code server.forward-headers-strategy}가 있어야 그 판정이 맞는다.
     */
    @Bean
    CsrfTokenRepository csrfTokenRepository() {
        CookieCsrfTokenRepository repository = CookieCsrfTokenRepository.withHttpOnlyFalse();
        repository.setCookieName("XSRF-TOKEN");
        repository.setHeaderName("X-XSRF-TOKEN");
        repository.setCookieCustomizer(builder -> builder.path("/").sameSite("Lax"));
        return repository;
    }

    private static void writeError(
            ObjectMapper objectMapper, HttpServletResponse response, ErrorCode errorCode)
            throws IOException {
        response.setStatus(errorCode.getStatus().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        objectMapper.writeValue(
                response.getOutputStream(), ApiResponse.fail(ErrorResponse.of(errorCode)));
    }

    private static void handleAccessDenied(
            ObjectMapper objectMapper,
            HttpServletResponse response,
            AccessDeniedException exception)
            throws IOException {
        if (exception instanceof CsrfException) {
            writeError(objectMapper, response, ErrorCode.CSRF_TOKEN_INVALID);
            return;
        }
        response.sendError(HttpStatus.FORBIDDEN.value());
    }

    /** SPA가 cookie의 원본 토큰을 header로 돌려보낼 수 있게 하면서 BREACH 방어용 XOR 토큰도 유지한다. */
    private static final class SpaCsrfTokenRequestHandler implements CsrfTokenRequestHandler {

        private final CsrfTokenRequestHandler plain = new CsrfTokenRequestAttributeHandler();
        private final CsrfTokenRequestHandler xor = new XorCsrfTokenRequestAttributeHandler();

        @Override
        public void handle(
                HttpServletRequest request,
                HttpServletResponse response,
                Supplier<CsrfToken> csrfToken) {
            xor.handle(request, response, csrfToken);
            csrfToken.get();
        }

        @Override
        public String resolveCsrfTokenValue(HttpServletRequest request, CsrfToken csrfToken) {
            return request.getHeader(csrfToken.getHeaderName()) != null
                    ? plain.resolveCsrfTokenValue(request, csrfToken)
                    : xor.resolveCsrfTokenValue(request, csrfToken);
        }
    }
}
