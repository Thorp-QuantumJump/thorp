package com.ssafy.thorp.account.presentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.ssafy.thorp.account.domain.model.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 제공자 콜백의 처리기와 세션 경계(T098, FR-026, spec 014 analyze G1).
 *
 * <p>가장 중요한 단언은 "로그인 판정이 아니면 세션이 인증되지 않는다"이다. Spring Security 필터는 처리기를 부르기 전에 OAuth2 인증을 세션에 저장하는데,
 * 그 인증이 남으면 {@code .authenticated()}만 보는 {@code /my-records/**}가 가입 대기 중인 사용자를 통과시킨다.
 */
class SocialLoginCallbackTest extends SocialLoginIntegrationTestSupport {

    private static final long KAKAO_ID = 4100000001L;

    @Test
    @DisplayName("연결된 카카오 계정은 세션 식별자를 바꿔 로그인하고 완료 화면으로 보낸다")
    void linkedAccountLogsInWithRotatedSession() throws Exception {
        User owner = saveUser(PREFIX + "linked@example.com", "01099000001", "password123");
        linkKakao(owner.getId(), KAKAO_ID);

        String state = startKakao("");
        String anonymousSession = cookies.get("THORP_SESSION").getValue();
        NEXT_USER.putAll(kakaoUser(KAKAO_ID, PREFIX + "linked@example.com", true, "카카오"));
        MvcResult callback =
                send(
                        get("/auth/oauth2/callback/kakao")
                                .param("code", "it-code")
                                .param("state", state));

        assertThat(callback.getResponse().getRedirectedUrl()).isEqualTo("/auth/social/complete");
        assertThat(cookies.get("THORP_SESSION").getValue()).isNotEqualTo(anonymousSession);
        MvcResult me = send(get("/auth/me"));
        assertThat(me.getResponse().getStatus()).isEqualTo(200);
        assertThat(me.getResponse().getContentAsString()).contains("\"userId\":" + owner.getId());
    }

    @Test
    @DisplayName("가입이 필요한 판정은 세션을 인증하지 않아 내 기록과 현재 사용자가 401이다")
    void signupOutcomeLeavesSessionUnauthenticated() throws Exception {
        String target =
                loginWithKakao(kakaoUser(KAKAO_ID, PREFIX + "new@example.com", true, "새회원"));

        assertThat(target).isEqualTo("/signup/social");
        assertThat(send(get("/auth/me")).getResponse().getStatus()).isEqualTo(401);
        assertThat(send(get("/my-records")).getResponse().getStatus()).isEqualTo(401);
    }

    @Test
    @DisplayName("기존 계정과 이메일이 같은 판정도 세션을 인증하지 않는다")
    void linkOutcomeLeavesSessionUnauthenticated() throws Exception {
        saveUser(PREFIX + "owner@example.com", "01099000002", "password123");

        String target =
                loginWithKakao(kakaoUser(KAKAO_ID, PREFIX + "owner@example.com", true, "닉네임"));

        assertThat(target).isEqualTo("/auth/social/link");
        assertThat(send(get("/auth/me")).getResponse().getStatus()).isEqualTo(401);
        assertThat(send(get("/my-records")).getResponse().getStatus()).isEqualTo(401);
    }

    @Test
    @DisplayName("이메일을 받지 못하면 거절 화면으로 보내고 대기 상태를 만들지 않는다")
    void missingEmailGoesToRejectionScreen() throws Exception {
        String target = loginWithKakao(kakaoUser(KAKAO_ID, null, false, "닉네임"));

        assertThat(target).isEqualTo("/auth/social/email-required?provider=kakao");
        assertThat(send(get("/auth/social/pending")).getResponse().getStatus()).isEqualTo(404);
        assertThat(send(get("/auth/me")).getResponse().getStatus()).isEqualTo(401);
    }

    @Test
    @DisplayName("같은 제공자의 다른 계정이 연결된 기존 계정이면 이미 연결 안내로 보낸다")
    void sameProviderConflictGoesToNotice() throws Exception {
        User owner = saveUser(PREFIX + "conflict@example.com", "01099000003", "password123");
        linkKakao(owner.getId(), 4199999999L);

        String target =
                loginWithKakao(kakaoUser(KAKAO_ID, PREFIX + "conflict@example.com", true, "닉네임"));

        assertThat(target).isEqualTo("/auth/social/already-linked?provider=kakao");
    }

    @Test
    @DisplayName("제공자 화면에서 취소하면 오류가 아니라 취소 안내로 돌려보낸다")
    void accessDeniedIsCancel() throws Exception {
        String state = startKakao("");

        MvcResult callback =
                send(
                        get("/auth/oauth2/callback/kakao")
                                .param("error", "access_denied")
                                .param("state", state));

        assertThat(callback.getResponse().getRedirectedUrl()).isEqualTo("/login?social=cancelled");
    }

    @Test
    @DisplayName("서비스가 시작하지 않은 state의 콜백은 로그인시키지 않고 일반 오류로 보낸다")
    void foreignStateIsRejected() throws Exception {
        startKakao("");
        NEXT_USER.putAll(kakaoUser(KAKAO_ID, PREFIX + "x@example.com", true, "닉네임"));

        MvcResult callback =
                send(
                        get("/auth/oauth2/callback/kakao")
                                .param("code", "it-code")
                                .param("state", "forged-state"));

        assertThat(callback.getResponse().getRedirectedUrl()).isEqualTo("/login?social=error");
        assertThat(send(get("/auth/me")).getResponse().getStatus()).isEqualTo(401);
    }

    @Test
    @DisplayName("같은 state를 두 번 쓰면 두 번째는 거절한다")
    void replayedStateIsRejected() throws Exception {
        NEXT_USER.putAll(kakaoUser(KAKAO_ID, PREFIX + "replay@example.com", true, "닉네임"));
        String state = startKakao("");
        send(get("/auth/oauth2/callback/kakao").param("code", "c1").param("state", state));

        MvcResult replay =
                send(get("/auth/oauth2/callback/kakao").param("code", "c2").param("state", state));

        assertThat(replay.getResponse().getRedirectedUrl()).isEqualTo("/login?social=error");
    }

    @Test
    @DisplayName("콜백이 보내는 주소에는 인가 코드와 state가 남지 않는다")
    void redirectCarriesNoCodeOrState() throws Exception {
        String target =
                loginWithKakao(kakaoUser(KAKAO_ID, PREFIX + "clean@example.com", true, "닉네임"));

        assertThat(target).doesNotContain("code=").doesNotContain("state=");
    }

    @Test
    @DisplayName("redirect URI는 요청 주소가 아니라 설정한 서비스 주소로 만든다")
    void redirectUriUsesPublicBaseUrl() throws Exception {
        MvcResult start = send(get("/auth/oauth2/authorization/kakao"));

        assertThat(start.getResponse().getRedirectedUrl())
                .contains("redirect_uri=http://localhost:5173/api/auth/oauth2/callback/kakao");
    }

    @Test
    @DisplayName("다시 동의하기는 카카오에 이메일 scope만 다시 요청한다")
    void repromptRequestsEmailScopeOnly() throws Exception {
        MvcResult start = send(get("/auth/oauth2/authorization/kakao").param("reprompt", "true"));

        assertThat(start.getResponse().getRedirectedUrl())
                .contains("scope=account_email")
                .doesNotContain("profile_nickname");
    }

    @Test
    @DisplayName("이미 로그인한 사용자가 소셜 로그인을 시작하면 /home으로 보낸다")
    void loggedInUserIsSentHome() throws Exception {
        User owner = saveUser(PREFIX + "home@example.com", "01099000004", "password123");
        linkKakao(owner.getId(), KAKAO_ID);
        loginWithKakao(kakaoUser(KAKAO_ID, PREFIX + "home@example.com", true, "닉네임"));

        MvcResult start = send(get("/auth/oauth2/authorization/kakao"));

        assertThat(start.getResponse().getRedirectedUrl()).isEqualTo("/home");
    }

    @Test
    @DisplayName("켜지지 않은 제공자의 시작 요청은 404 SOCIAL_PROVIDER_NOT_ENABLED다")
    void disabledProviderIsNotFound() throws Exception {
        MvcResult start = send(get("/auth/oauth2/authorization/naver"));

        assertThat(start.getResponse().getStatus()).isEqualTo(404);
        assertThat(start.getResponse().getContentAsString())
                .contains("SOCIAL_PROVIDER_NOT_ENABLED");
    }

    @Test
    @DisplayName("제공자 목록은 자격 증명이 설정된 제공자만 돌려준다")
    void providersListsOnlyConfigured() throws Exception {
        MvcResult providers = send(get("/auth/social/providers"));

        assertThat(providers.getResponse().getContentAsString())
                .contains("\"providers\":[\"kakao\"]");
    }
}
