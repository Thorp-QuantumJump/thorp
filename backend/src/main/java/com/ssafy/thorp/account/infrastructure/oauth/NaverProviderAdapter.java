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
 *   <li><b>확인된 이메일로 본다</b>(clarify 2026-10-02, FR-022). 응답에 확인 여부 필드는 없지만, 이 값은 사용자가 고른 연락처 이메일이고
 *       네이버 메일이거나 네이버가 등록 때 인증 메일로 확인한 외부 주소 둘뿐이다. 그래서 Thorp 인증번호를 다시 받지 않는다.
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
                EmailStatus.VERIFIED,
                OAuth2Attributes.text(response.get("nickname")));
    }

    @Override
    public void requestEmailConsentAgain(OAuth2AuthorizationRequest.Builder builder) {
        builder.additionalParameters(parameters -> parameters.put("auth_type", "reprompt"));
    }
}
