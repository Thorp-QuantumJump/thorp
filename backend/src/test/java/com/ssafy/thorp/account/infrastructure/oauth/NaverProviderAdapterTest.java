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

/** 네이버 응답을 제공자와 무관한 값으로 바꾸는 규칙(T081·T083). */
class NaverProviderAdapterTest {

    private final NaverProviderAdapter adapter = new NaverProviderAdapter();

    @Test
    @DisplayName("response.id·email·nickname을 옮기고 네이버 메일은 확인된 이메일로 둔다")
    void extractsResponseBlockAsVerifiedEmail() {
        SocialProfile profile = adapter.extract(naverUser("member@naver.com", "네이버"));

        assertThat(profile.provider()).isEqualTo(SocialProvider.NAVER);
        assertThat(profile.providerUserId()).isEqualTo("naver-abc123");
        assertThat(profile.email()).isEqualTo("member@naver.com");
        assertThat(profile.emailStatus()).isEqualTo(EmailStatus.VERIFIED);
        assertThat(profile.nickname()).isEqualTo("네이버");
    }

    @Test
    @DisplayName("외부 연락처 이메일도 네이버가 등록 때 인증 메일로 확인하므로 확인된 이메일로 둔다")
    void externalContactEmailIsVerified() {
        SocialProfile profile = adapter.extract(naverUser("member@gmail.com", "네이버"));

        assertThat(profile.emailStatus()).isEqualTo(EmailStatus.VERIFIED);
    }

    @Test
    @DisplayName("이메일 제공에 동의하지 않으면 이메일 없음으로 둔다")
    void missingEmail() {
        assertThat(adapter.extract(naverUser(null, "네이버")).hasEmail()).isFalse();
    }

    @Test
    @DisplayName("등록은 네이버 주소·client_secret_post·response 아래 식별자를 쓴다")
    void registrationUsesNaverEndpoints() {
        ClientRegistration registration =
                adapter.registration()
                        .clientId("id")
                        .clientSecret("secret")
                        .redirectUri(
                                "http://localhost:5173/api/auth/oauth2/callback/{registrationId}")
                        .build();

        assertThat(registration.getRegistrationId()).isEqualTo("naver");
        assertThat(registration.getClientAuthenticationMethod())
                .isEqualTo(ClientAuthenticationMethod.CLIENT_SECRET_POST);
        assertThat(registration.getProviderDetails().getTokenUri())
                .isEqualTo("https://nid.naver.com/oauth2.0/token");
        assertThat(
                        registration
                                .getProviderDetails()
                                .getUserInfoEndpoint()
                                .getUserNameAttributeName())
                .isEqualTo("response");
    }

    @Test
    @DisplayName("이메일 재동의는 auth_type=reprompt를 더한다")
    void repromptAddsAuthType() {
        OAuth2AuthorizationRequest.Builder builder =
                OAuth2AuthorizationRequest.authorizationCode()
                        .authorizationUri("https://nid.naver.com/oauth2.0/authorize")
                        .clientId("id");

        adapter.requestEmailConsentAgain(builder);

        assertThat(builder.build().getAdditionalParameters())
                .containsEntry("auth_type", "reprompt");
    }

    private static OAuth2User naverUser(String email, String nickname) {
        Map<String, Object> response = new HashMap<>();
        response.put("id", "naver-abc123");
        response.put("nickname", nickname);
        if (email != null) {
            response.put("email", email);
        }
        return new DefaultOAuth2User(
                List.of(),
                Map.of("resultcode", "00", "message", "success", "response", response),
                "response");
    }
}
