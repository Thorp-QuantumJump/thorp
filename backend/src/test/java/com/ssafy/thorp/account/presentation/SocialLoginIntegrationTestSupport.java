package com.ssafy.thorp.account.presentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.ssafy.thorp.account.application.port.VerificationMailSender;
import com.ssafy.thorp.account.application.repository.UserRepository;
import com.ssafy.thorp.account.domain.model.User;
import com.ssafy.thorp.account.domain.model.UserStatus;
import com.ssafy.thorp.support.IntegrationTestSupport;
import jakarta.servlet.http.Cookie;
import java.net.URI;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.client.endpoint.OAuth2AccessTokenResponseClient;
import org.springframework.security.oauth2.client.endpoint.OAuth2AuthorizationCodeGrantRequest;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.endpoint.OAuth2AccessTokenResponse;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * 소셜 로그인 통합 테스트의 공통 기반. <b>상속한 테스트는 컨텍스트 하나를 함께 쓴다.</b>
 *
 * <p>카카오만 켠다(가짜 자격 증명). 제공자와 실제로 통신하는 두 지점 — token 교환과 사용자 정보 조회 — 만 가짜 빈으로 바꾸고, 시작 요청·state 보관·콜백
 * 검증·성공/실패 처리기·세션 저장은 운영과 같은 필터를 그대로 지난다. Spring Security는 두 지점의 빈이 있으면 기본 구현 대신 그 빈을 쓴다.
 *
 * <p>브라우저처럼 쿠키를 들고 다닌다. 응답의 {@code Set-Cookie}로 쿠키를 갱신하므로 세션 식별자 교체가 그대로 따라온다.
 */
@TestPropertySource(
        properties = {
            "thorp.auth.social.public-base-url="
                    + SocialLoginIntegrationTestSupport.PUBLIC_BASE_URL,
            "thorp.auth.social.kakao.client-id=it-kakao-id",
            "thorp.auth.social.kakao.client-secret=it-kakao-secret"
        })
@Import(SocialLoginIntegrationTestSupport.StubProvider.class)
abstract class SocialLoginIntegrationTestSupport extends IntegrationTestSupport {

    static final String PREFIX = "social-it-";

    /** 다음 사용자 정보 조회가 돌려줄 카카오 응답. 테스트는 순서대로 돌므로 하나면 된다. */
    static final Map<String, Object> NEXT_USER = new HashMap<>();

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired UserRepository userRepository;
    @Autowired PasswordEncoder passwordEncoder;

    @MockitoBean VerificationMailSender mailSender;

    final Map<String, Cookie> cookies = new LinkedHashMap<>();

    @BeforeEach
    void resetSocialState() {
        cookies.clear();
        NEXT_USER.clear();
        jdbcTemplate.update(
                "delete from social_accounts where user_id in"
                        + " (select id from users where email like ?)",
                PREFIX + "%");
        jdbcTemplate.update(
                "delete from social_link_targets where user_id in"
                        + " (select id from users where email like ?)",
                PREFIX + "%");
        jdbcTemplate.update("delete from social_pending_verifications");
        jdbcTemplate.update("delete from users where email like ?", PREFIX + "%");
        jdbcTemplate.update("delete from spring_session");
    }

    /** 상태를 바꾸는 요청. SPA처럼 {@code XSRF-TOKEN} 쿠키 값을 헤더로 돌려보낸다. */
    MvcResult sendWithCsrf(MockHttpServletRequestBuilder builder) throws Exception {
        if (!cookies.containsKey("XSRF-TOKEN")) {
            send(get("/auth/csrf"));
        }
        return send(builder.header("X-XSRF-TOKEN", cookies.get("XSRF-TOKEN").getValue()));
    }

    /** 브라우저가 쿠키를 싣고 보내듯 요청하고, 응답의 쿠키로 쿠키 상자를 갱신한다. */
    MvcResult send(MockHttpServletRequestBuilder builder) throws Exception {
        MockHttpServletRequestBuilder request = builder;
        if (!cookies.isEmpty()) {
            request = request.cookie(cookies.values().toArray(Cookie[]::new));
        }
        MvcResult result = mockMvc.perform(request).andReturn();
        for (Cookie cookie : result.getResponse().getCookies()) {
            if (cookie.getMaxAge() == 0) {
                cookies.remove(cookie.getName());
            } else {
                cookies.put(cookie.getName(), cookie);
            }
        }
        return result;
    }

    /**
     * 카카오 로그인을 처음부터 끝까지 지나고 콜백이 보낸 FE 주소를 돌려준다.
     *
     * @param kakaoUser 카카오 {@code /v2/user/me} 응답
     */
    String loginWithKakao(Map<String, Object> kakaoUser) throws Exception {
        NEXT_USER.clear();
        NEXT_USER.putAll(kakaoUser);
        String state = startKakao("");
        MvcResult callback =
                send(
                        get("/auth/oauth2/callback/kakao")
                                .param("code", "it-authorization-code")
                                .param("state", state));
        assertThat(callback.getResponse().getStatus()).isEqualTo(302);
        return pathOf(callback);
    }

    static final String PUBLIC_BASE_URL = "http://localhost:5173";

    /**
     * 서버가 FE 화면으로 보낸 주소에서 서비스 주소를 뗀 경로. 서버는 서비스 주소 기준 절대 주소로 보내야 한다 — 상대 경로면 Tomcat이 요청 Host로 절대
     * 주소를 만들어 로컬에서 백엔드 포트로 떨어진다.
     */
    static String pathOf(MvcResult result) {
        String location = result.getResponse().getRedirectedUrl();
        assertThat(location).startsWith(PUBLIC_BASE_URL + "/");
        return location.substring(PUBLIC_BASE_URL.length());
    }

    /** 시작 요청을 보내고 제공자 인가 주소의 state를 돌려준다. */
    String startKakao(String query) throws Exception {
        return startKakao(get("/auth/oauth2/authorization/kakao" + query));
    }

    /**
     * 돌아갈 자리를 실어 시작한다. 질의 문자열이 아니라 {@code .param} 으로 싣는다 — MockMvc 는 URL 문자열을 템플릿으로 보고 한 번 더 인코딩하므로
     * {@code %2F} 가 {@code %252F} 로 들어가 서버가 보는 값이 브라우저가 보낸 것과 달라진다.
     */
    String startKakaoReturningTo(String returnTo) throws Exception {
        return startKakao(get("/auth/oauth2/authorization/kakao").param("returnTo", returnTo));
    }

    private String startKakao(MockHttpServletRequestBuilder request) throws Exception {
        MvcResult start = send(request);
        assertThat(start.getResponse().getStatus()).isEqualTo(302);
        String location = start.getResponse().getRedirectedUrl();
        assertThat(location).startsWith("https://kauth.kakao.com/oauth/authorize");
        // 인가 주소의 쿼리 값은 인코딩되어 있다. 브라우저는 제공자가 돌려준 값을 그대로 콜백에 싣으므로 원래 값으로 되돌려 넘긴다.
        String encoded =
                UriComponentsBuilder.fromUri(URI.create(location))
                        .build()
                        .getQueryParams()
                        .getFirst("state");
        return java.net.URLDecoder.decode(encoded, java.nio.charset.StandardCharsets.UTF_8);
    }

    static Map<String, Object> kakaoUser(long id, String email, boolean verified, String nickname) {
        Map<String, Object> account = new HashMap<>();
        account.put("profile", Map.of("nickname", nickname));
        if (email != null) {
            account.put("email", email);
            account.put("is_email_valid", true);
            account.put("is_email_verified", verified);
        }
        Map<String, Object> attributes = new HashMap<>();
        attributes.put("id", id);
        attributes.put("kakao_account", account);
        return attributes;
    }

    User saveUser(String email, String phone, String rawPassword) {
        return userRepository.save(
                new User(
                        null,
                        email,
                        rawPassword == null ? null : passwordEncoder.encode(rawPassword),
                        "기존회원",
                        phone,
                        null,
                        UserStatus.ACTIVE,
                        false,
                        false,
                        false,
                        null,
                        Instant.now(),
                        Instant.now(),
                        null));
    }

    void linkKakao(Long userId, long kakaoId) {
        jdbcTemplate.update(
                "insert into social_accounts (user_id, provider, provider_user_id, linked_at)"
                        + " values (?, 'KAKAO', ?, now())",
                userId,
                String.valueOf(kakaoId));
    }

    /** 제공자와 통신하는 두 지점만 바꾼다. */
    @TestConfiguration
    static class StubProvider {

        @Bean
        OAuth2AccessTokenResponseClient<OAuth2AuthorizationCodeGrantRequest> stubTokenClient() {
            return request ->
                    OAuth2AccessTokenResponse.withToken("it-access-token")
                            .tokenType(OAuth2AccessToken.TokenType.BEARER)
                            .expiresIn(60)
                            .build();
        }

        @Bean
        OAuth2UserService<OAuth2UserRequest, OAuth2User> stubUserService() {
            return request -> new DefaultOAuth2User(List.of(), Map.copyOf(NEXT_USER), "id");
        }
    }
}
