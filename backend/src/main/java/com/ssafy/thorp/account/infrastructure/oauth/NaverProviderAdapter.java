package com.ssafy.thorp.account.infrastructure.oauth;

import com.ssafy.thorp.account.application.command.SocialProfile;
import com.ssafy.thorp.account.application.command.SocialProfile.EmailStatus;
import com.ssafy.thorp.account.domain.model.SocialProvider;
import java.util.Map;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Component;

/**
 * 네이버 로그인. 사용자 정보는 {@code GET /v1/nid/me}의 {@code response} 아래에 있다.
 *
 * <ul>
 *   <li>사용자 식별자: {@code response.id}
 *   <li>이메일: {@code response.email}. 사용자가 제공에 동의하지 않으면 없다.
 *   <li><b>확인 여부를 알려주지 않는다.</b> 그래서 네이버 이메일은 언제나 Thorp 인증번호로 확인한 뒤 가입·연결로 간다(clarify 2026-10-01,
 *       FR-022).
 *   <li>닉네임: {@code response.nickname}
 * </ul>
 *
 * <p>scope를 쓰지 않는다 — 받는 항목은 네이버 개발자 센터의 앱 설정이 정한다. 이메일 재동의는 {@code auth_type=reprompt}다.
 */
@Component
public class NaverProviderAdapter implements SocialProviderAdapter {

    @Override
    public SocialProvider provider() {
        return SocialProvider.NAVER;
    }

    @Override
    public ClientRegistration.Builder registration() {
        return ClientRegistration.withRegistrationId(provider().registrationId())
                .clientName("Naver")
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_POST)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .authorizationUri("https://nid.naver.com/oauth2.0/authorize")
                .tokenUri("https://nid.naver.com/oauth2.0/token")
                .userInfoUri("https://openapi.naver.com/v1/nid/me")
                .userNameAttributeName("response");
    }

    @Override
    public SocialProfile extract(OAuth2User user) {
        Map<?, ?> response = OAuth2Attributes.map(user.getAttributes().get("response"));
        return new SocialProfile(
                provider(),
                OAuth2Attributes.text(response.get("id")),
                OAuth2Attributes.text(response.get("email")),
                EmailStatus.UNKNOWN,
                OAuth2Attributes.text(response.get("nickname")));
    }

    @Override
    public void requestEmailConsentAgain(OAuth2AuthorizationRequest.Builder builder) {
        builder.additionalParameters(parameters -> parameters.put("auth_type", "reprompt"));
    }
}
