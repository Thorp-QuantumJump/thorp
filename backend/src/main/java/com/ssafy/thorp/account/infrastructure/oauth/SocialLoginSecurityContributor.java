package com.ssafy.thorp.account.infrastructure.oauth;

import com.ssafy.thorp.common.config.HttpSecurityContributor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.stereotype.Component;

/**
 * 공통 보안 경계에 소셜 로그인 필터를 붙인다(spec 014 FR-020, plan Provider integration).
 *
 * <p>외부 경로는 {@code /api/auth/oauth2/...}이고 proxy가 {@code /api}를 떼므로 여기서는 내부 경로 {@code
 * /auth/oauth2/...}를 쓴다. 제공자에게 넘기는 redirect URI는 {@link
 * SocialAuthProperties#redirectUriTemplate()}의 외부 주소다. Spring Security는 콜백에서 state만 대조하고 redirect
 * URI를 요청 주소와 비교하지 않으므로 두 경로가 달라도 된다.
 *
 * <p>{@code loginPage}를 지정하는 이유는 기본 로그인 화면 생성기를 끄기 위해서다. 인증 진입점은 {@code SecurityConfig}가 401 JSON으로
 * 정해 두었다.
 */
@Component
@EnableConfigurationProperties(SocialAuthProperties.class)
public class SocialLoginSecurityContributor implements HttpSecurityContributor {

    static final String CALLBACK_URI = "/auth/oauth2/callback/*";

    private final SocialClientRegistrations registrations;
    private final SocialAuthorizationRequestResolver authorizationRequestResolver;
    private final SocialLoginSuccessHandler successHandler;
    private final SocialLoginFailureHandler failureHandler;

    public SocialLoginSecurityContributor(
            SocialClientRegistrations registrations,
            SocialAuthorizationRequestResolver authorizationRequestResolver,
            SocialLoginSuccessHandler successHandler,
            SocialLoginFailureHandler failureHandler) {
        this.registrations = registrations;
        this.authorizationRequestResolver = authorizationRequestResolver;
        this.successHandler = successHandler;
        this.failureHandler = failureHandler;
    }

    @Override
    public void contribute(HttpSecurity http) throws Exception {
        http.oauth2Login(
                oauth ->
                        oauth.clientRegistrationRepository(registrations)
                                .authorizedClientRepository(
                                        new DiscardingAuthorizedClientRepository())
                                .loginPage("/login")
                                .authorizationEndpoint(
                                        endpoint ->
                                                endpoint.baseUri(
                                                                SocialAuthorizationRequestResolver
                                                                        .BASE_URI)
                                                        .authorizationRequestResolver(
                                                                authorizationRequestResolver))
                                .redirectionEndpoint(endpoint -> endpoint.baseUri(CALLBACK_URI))
                                .successHandler(successHandler)
                                .failureHandler(failureHandler));
    }
}
