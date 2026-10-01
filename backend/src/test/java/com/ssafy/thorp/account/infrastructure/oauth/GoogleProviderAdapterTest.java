package com.ssafy.thorp.account.infrastructure.oauth;

import static org.assertj.core.api.Assertions.assertThat;

import com.ssafy.thorp.account.application.command.SocialProfile;
import com.ssafy.thorp.account.application.command.SocialProfile.EmailStatus;
import com.ssafy.thorp.account.domain.model.SocialProvider;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.oauth2.core.user.OAuth2User;

/** 구글 OIDC 응답을 제공자와 무관한 값으로 바꾸는 규칙(T078·T080). */
class GoogleProviderAdapterTest {

    private final GoogleProviderAdapter adapter = new GoogleProviderAdapter();

    @Test
    @DisplayName("sub를 식별자로, 확인된 이메일과 name을 옮긴다")
    void extractsVerifiedEmail() {
        SocialProfile profile = adapter.extract(googleUser("member@gmail.com", true, "구글회원"));

        assertThat(profile.provider()).isEqualTo(SocialProvider.GOOGLE);
        assertThat(profile.providerUserId()).isEqualTo("109876543210987654321");
        assertThat(profile.email()).isEqualTo("member@gmail.com");
        assertThat(profile.emailStatus()).isEqualTo(EmailStatus.VERIFIED);
        assertThat(profile.nickname()).isEqualTo("구글회원");
    }

    @Test
    @DisplayName("email_verified가 거짓이면 미확인 이메일이다")
    void unverifiedEmail() {
        assertThat(adapter.extract(googleUser("m@gmail.com", false, "닉")).emailStatus())
                .isEqualTo(EmailStatus.UNVERIFIED);
    }

    @Test
    @DisplayName("email_verified가 문자열로 와도 같은 뜻으로 읽는다")
    void stringVerifiedFlag() {
        Map<String, Object> attributes = new HashMap<>(attributes("m@gmail.com", true, "닉"));
        attributes.put("email_verified", "true");

        assertThat(adapter.extract(user(attributes)).emailStatus()).isEqualTo(EmailStatus.VERIFIED);
    }

    @Test
    @DisplayName("이메일이 없으면 이메일 없음으로 둔다")
    void missingEmail() {
        assertThat(adapter.extract(googleUser(null, false, "닉")).hasEmail()).isFalse();
    }

    @Test
    @DisplayName("등록은 구글 OIDC 기본값(openid·email scope, sub)을 쓴다")
    void registrationUsesGoogleDefaults() {
        ClientRegistration registration =
                adapter.registration()
                        .clientId("id")
                        .clientSecret("secret")
                        .redirectUri(
                                "http://localhost:5173/api/auth/oauth2/callback/{registrationId}")
                        .build();

        assertThat(registration.getRegistrationId()).isEqualTo("google");
        assertThat(registration.getScopes()).contains("openid", "email");
        assertThat(
                        registration
                                .getProviderDetails()
                                .getUserInfoEndpoint()
                                .getUserNameAttributeName())
                .isEqualTo("sub");
    }

    @Test
    @DisplayName("이메일 재동의는 prompt=consent를 더하고 기존 파라미터는 지우지 않는다")
    void repromptAddsConsentPrompt() {
        OAuth2AuthorizationRequest.Builder builder =
                OAuth2AuthorizationRequest.authorizationCode()
                        .authorizationUri("https://accounts.google.com/o/oauth2/v2/auth")
                        .clientId("id")
                        .scopes(Set.of("openid", "email"))
                        .additionalParameters(Map.of("nonce", "n-1"));

        adapter.requestEmailConsentAgain(builder);

        assertThat(builder.build().getAdditionalParameters())
                .containsEntry("prompt", "consent")
                .containsEntry("nonce", "n-1");
    }

    private static OAuth2User googleUser(String email, boolean verified, String name) {
        return user(attributes(email, verified, name));
    }

    private static Map<String, Object> attributes(String email, boolean verified, String name) {
        Map<String, Object> attributes = new HashMap<>();
        attributes.put("sub", "109876543210987654321");
        attributes.put("name", name);
        if (email != null) {
            attributes.put("email", email);
            attributes.put("email_verified", verified);
        }
        return attributes;
    }

    private static OAuth2User user(Map<String, Object> attributes) {
        return new DefaultOAuth2User(List.of(), attributes, "sub");
    }
}
