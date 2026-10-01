package com.ssafy.thorp.account.infrastructure.oauth;

import static org.assertj.core.api.Assertions.assertThat;

import com.ssafy.thorp.account.application.command.SocialProfile;
import com.ssafy.thorp.account.application.command.SocialProfile.EmailStatus;
import com.ssafy.thorp.account.domain.model.SocialProvider;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.oauth2.core.user.OAuth2User;

/** 카카오 응답을 제공자와 무관한 값으로 바꾸는 규칙(T075·T077). 응답 형태는 카카오 {@code /v2/user/me} 문서를 따른다. */
class KakaoProviderAdapterTest {

    private final KakaoProviderAdapter adapter = new KakaoProviderAdapter();

    @Test
    @DisplayName("숫자 id를 문자열 식별자로, 확인된 이메일과 닉네임을 그대로 옮긴다")
    void extractsVerifiedEmailAndNickname() {
        SocialProfile profile = adapter.extract(kakaoUser("member@example.com", true, true, "카카오"));

        assertThat(profile.provider()).isEqualTo(SocialProvider.KAKAO);
        assertThat(profile.providerUserId()).isEqualTo("4100000001");
        assertThat(profile.email()).isEqualTo("member@example.com");
        assertThat(profile.emailStatus()).isEqualTo(EmailStatus.VERIFIED);
        assertThat(profile.nickname()).isEqualTo("카카오");
    }

    @Test
    @DisplayName("is_email_verified나 is_email_valid가 거짓이면 미확인 이메일이다")
    void unverifiedOrInvalidEmailIsUnverified() {
        assertThat(adapter.extract(kakaoUser("m@example.com", true, false, "닉")).emailStatus())
                .isEqualTo(EmailStatus.UNVERIFIED);
        assertThat(adapter.extract(kakaoUser("m@example.com", false, true, "닉")).emailStatus())
                .isEqualTo(EmailStatus.UNVERIFIED);
    }

    @Test
    @DisplayName("이메일 동의를 하지 않아 email이 없으면 이메일 없음으로 둔다")
    void missingEmailIsNull() {
        SocialProfile profile = adapter.extract(kakaoUser(null, null, null, "닉"));

        assertThat(profile.hasEmail()).isFalse();
        assertThat(profile.emailStatus()).isEqualTo(EmailStatus.UNKNOWN);
    }

    @Test
    @DisplayName("kakao_account가 통째로 없어도 실패하지 않고 이메일·닉네임 없음으로 둔다")
    void missingAccountBlockIsTolerated() {
        OAuth2User user = new DefaultOAuth2User(List.of(), Map.of("id", 4100000001L), "id");

        SocialProfile profile = adapter.extract(user);

        assertThat(profile.hasEmail()).isFalse();
        assertThat(profile.nickname()).isNull();
    }

    @Test
    @DisplayName("등록은 카카오 주소·client_secret_post·이메일 scope를 쓴다")
    void registrationUsesKakaoEndpoints() {
        ClientRegistration registration =
                adapter.registration()
                        .clientId("id")
                        .clientSecret("secret")
                        .redirectUri(
                                "http://localhost:5173/api/auth/oauth2/callback/{registrationId}")
                        .build();

        assertThat(registration.getRegistrationId()).isEqualTo("kakao");
        assertThat(registration.getClientAuthenticationMethod())
                .isEqualTo(ClientAuthenticationMethod.CLIENT_SECRET_POST);
        assertThat(registration.getScopes()).contains("account_email");
        assertThat(registration.getProviderDetails().getAuthorizationUri())
                .isEqualTo("https://kauth.kakao.com/oauth/authorize");
        assertThat(registration.getProviderDetails().getUserInfoEndpoint().getUri())
                .isEqualTo("https://kapi.kakao.com/v2/user/me");
    }

    @Test
    @DisplayName("이메일 재동의는 scope=account_email만 실어 다시 묻는다")
    void repromptAsksForEmailScopeOnly() {
        OAuth2AuthorizationRequest.Builder builder =
                OAuth2AuthorizationRequest.authorizationCode()
                        .authorizationUri("https://kauth.kakao.com/oauth/authorize")
                        .clientId("id")
                        .scopes(java.util.Set.of("profile_nickname", "account_email"));

        adapter.requestEmailConsentAgain(builder);

        assertThat(builder.build().getScopes()).containsExactly("account_email");
    }

    static OAuth2User kakaoUser(String email, Boolean valid, Boolean verified, String nickname) {
        Map<String, Object> account = new HashMap<>();
        account.put("profile", Map.of("nickname", nickname));
        if (email != null) {
            account.put("email", email);
            account.put("has_email", true);
        }
        if (valid != null) {
            account.put("is_email_valid", valid);
        }
        if (verified != null) {
            account.put("is_email_verified", verified);
        }
        Map<String, Object> attributes = new HashMap<>();
        attributes.put("id", 4100000001L);
        attributes.put("kakao_account", account);
        return new DefaultOAuth2User(List.of(), attributes, "id");
    }
}
