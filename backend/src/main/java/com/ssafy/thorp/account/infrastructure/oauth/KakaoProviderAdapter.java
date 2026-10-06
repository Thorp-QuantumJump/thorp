package com.ssafy.thorp.account.infrastructure.oauth;

import com.ssafy.thorp.account.application.command.SocialProfile;
import com.ssafy.thorp.account.application.command.SocialProfile.EmailStatus;
import com.ssafy.thorp.account.domain.model.SocialProvider;
import java.util.Map;
import java.util.Set;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Component;

/**
 * 카카오 로그인(REST API). 사용자 정보는 {@code GET /v2/user/me}의 응답이다.
 *
 * <ul>
 *   <li>사용자 식별자: 최상위 {@code id}(숫자)
 *   <li>이메일: {@code kakao_account.email}. 사용자가 동의하지 않으면 없다 — 비즈 앱 전환 전에는 이메일이 선택 동의라 자주 빈다(spec
 *       Dependencies).
 *   <li>확인 여부: {@code is_email_valid}와 {@code is_email_verified}가 둘 다 참일 때만 확인된 이메일로 본다. 하나라도 거짓이면
 *       Thorp 인증번호로 확인한다(FR-022).
 *   <li>닉네임: {@code kakao_account.profile.nickname}
 * </ul>
 *
 * <p>이메일 재동의는 같은 인가 주소에 {@code scope=account_email}만 실어 다시 보내는 방식이다(카카오 "추가 항목 동의 받기").
 */
@Component
public class KakaoProviderAdapter implements SocialProviderAdapter {

    static final String EMAIL_SCOPE = "account_email";

    @Override
    public SocialProvider provider() {
        return SocialProvider.KAKAO;
    }

    @Override
    public ClientRegistration.Builder registration() {
        return ClientRegistration.withRegistrationId(provider().registrationId())
                .clientName("Kakao")
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_POST)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .scope("profile_nickname", EMAIL_SCOPE)
                .authorizationUri("https://kauth.kakao.com/oauth/authorize")
                .tokenUri("https://kauth.kakao.com/oauth/token")
                .userInfoUri("https://kapi.kakao.com/v2/user/me")
                .userNameAttributeName("id");
    }

    @Override
    public SocialProfile extract(OAuth2User user) {
        Map<String, Object> attributes = user.getAttributes();
        Map<?, ?> account = OAuth2Attributes.map(attributes.get("kakao_account"));
        Map<?, ?> profile = OAuth2Attributes.map(account.get("profile"));

        String email = OAuth2Attributes.text(account.get("email"));
        boolean verified =
                Boolean.TRUE.equals(account.get("is_email_valid"))
                        && Boolean.TRUE.equals(account.get("is_email_verified"));

        return new SocialProfile(
                provider(),
                OAuth2Attributes.text(attributes.get("id")),
                email,
                email == null
                        ? EmailStatus.UNKNOWN
                        : verified ? EmailStatus.VERIFIED : EmailStatus.UNVERIFIED,
                OAuth2Attributes.text(profile.get("nickname")));
    }

    @Override
    public void requestEmailConsentAgain(OAuth2AuthorizationRequest.Builder builder) {
        builder.scopes(Set.of(EMAIL_SCOPE));
    }
}
