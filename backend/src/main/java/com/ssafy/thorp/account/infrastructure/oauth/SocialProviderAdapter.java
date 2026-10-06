package com.ssafy.thorp.account.infrastructure.oauth;

import com.ssafy.thorp.account.application.command.SocialProfile;
import com.ssafy.thorp.account.domain.model.SocialProvider;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.oauth2.core.user.OAuth2User;

/**
 * 제공자 하나의 차이를 모은 자리. 제공자마다 구현이 하나씩 있다.
 *
 * <p>여기서 끝나는 차이: 인가·token·사용자 정보 주소, scope, 응답의 필드 이름, 이메일 재동의 파라미터. 이 밖의 판정은 모두 {@link
 * SocialProfile}을 받는 애플리케이션 서비스가 한다.
 */
public interface SocialProviderAdapter {

    SocialProvider provider();

    /** client id·secret·redirect URI를 뺀 등록 정보. 나머지는 {@link SocialClientRegistrations}가 채운다. */
    ClientRegistration.Builder registration();

    /** 제공자 응답을 제공자와 무관한 값으로 바꾼다. 이메일이 없으면 {@code null}로 둔다 — 거절 판정은 서비스가 한다. */
    SocialProfile extract(OAuth2User user);

    /** 이메일 제공 동의를 다시 묻도록 인가 요청을 고친다(FR-022 거절 화면의 "다시 동의"). */
    void requestEmailConsentAgain(OAuth2AuthorizationRequest.Builder builder);
}
