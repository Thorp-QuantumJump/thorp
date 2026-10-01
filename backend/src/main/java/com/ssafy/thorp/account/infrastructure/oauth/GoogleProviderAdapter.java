package com.ssafy.thorp.account.infrastructure.oauth;

import com.ssafy.thorp.account.application.command.SocialProfile;
import com.ssafy.thorp.account.application.command.SocialProfile.EmailStatus;
import com.ssafy.thorp.account.domain.model.SocialProvider;
import java.util.Map;
import org.springframework.security.config.oauth2.client.CommonOAuth2Provider;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Component;

/**
 * 구글 로그인(OpenID Connect). 주소·scope({@code openid profile email})·서명 키는 Spring의 구글 기본값을 쓴다.
 *
 * <ul>
 *   <li>사용자 식별자: ID token의 {@code sub}
 *   <li>이메일과 확인 여부: {@code email}, {@code email_verified}. 확인되지 않았으면 Thorp 인증번호로 확인한다(FR-022).
 *   <li>닉네임: {@code name}
 * </ul>
 *
 * <p>이메일 재동의는 {@code prompt=consent}로 동의 화면을 다시 띄운다. 구글은 {@code email} scope를 거부할 수 없어 거절 화면에 올 일이
 * 드물다.
 */
@Component
public class GoogleProviderAdapter implements SocialProviderAdapter {

    @Override
    public SocialProvider provider() {
        return SocialProvider.GOOGLE;
    }

    @Override
    public ClientRegistration.Builder registration() {
        return CommonOAuth2Provider.GOOGLE.getBuilder(provider().registrationId());
    }

    @Override
    public SocialProfile extract(OAuth2User user) {
        Map<String, Object> attributes = user.getAttributes();
        String email = OAuth2Attributes.text(attributes.get("email"));
        Object verified = attributes.get("email_verified");
        boolean isVerified =
                Boolean.TRUE.equals(verified) || "true".equalsIgnoreCase(String.valueOf(verified));
        return new SocialProfile(
                provider(),
                OAuth2Attributes.text(attributes.get("sub")),
                email,
                email == null
                        ? EmailStatus.UNKNOWN
                        : isVerified ? EmailStatus.VERIFIED : EmailStatus.UNVERIFIED,
                OAuth2Attributes.text(attributes.get("name")));
    }

    @Override
    public void requestEmailConsentAgain(OAuth2AuthorizationRequest.Builder builder) {
        builder.additionalParameters(parameters -> parameters.put("prompt", "consent"));
    }
}
