package com.ssafy.thorp.common.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ssafy.thorp.common.exception.ErrorCode;
import com.ssafy.thorp.common.web.response.ApiResponse;
import com.ssafy.thorp.common.web.response.ErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
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
            SecurityContextRepository securityContextRepository,
            SessionAuthenticationStrategy sessionAuthenticationStrategy)
            throws Exception {
        return http.csrf(
                        csrf ->
                                csrf.csrfTokenRepository(csrfTokenRepository())
                                        .csrfTokenRequestHandler(new SpaCsrfTokenRequestHandler()))
                .authorizeHttpRequests(
                        authorize ->
                                authorize
                                        .requestMatchers("/auth/me")
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

    private static CookieCsrfTokenRepository csrfTokenRepository() {
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
