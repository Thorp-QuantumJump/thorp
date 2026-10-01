package com.ssafy.thorp.account.infrastructure.oauth;

import com.ssafy.thorp.account.application.AuthenticatedPrincipal;
import com.ssafy.thorp.account.application.port.PendingSocialLoginStore;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Optional;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.stereotype.Component;

/**
 * 소셜 로그인 시작 요청({@code GET /auth/oauth2/authorization/{provider}})을 제공자 인가 요청으로 바꾼다.
 *
 * <p>기본 해석기에 세 가지를 더한다.
 *
 * <ul>
 *   <li>켜지지 않은 제공자와 이미 로그인한 사용자는 인가 요청을 만들지 않는다. 요청은 컨트롤러로 넘어가 404 또는 {@code /home} 이동으로 끝난다.
 *   <li>{@code reprompt=true}면 이메일 제공 동의를 다시 묻는다(FR-022).
 *   <li>{@code intent=link}면 콜백이 대기 중인 연결을 마치는 데 쓰도록 세션에 표시한다. 그 밖의 시작은 이전 대기 상태를 지운다(FR-025).
 * </ul>
 */
@Component
public class SocialAuthorizationRequestResolver implements OAuth2AuthorizationRequestResolver {

    static final String BASE_URI = "/auth/oauth2/authorization";
    static final String INTENT_ATTRIBUTE = "thorp.social.intent";
    static final String LINK_INTENT = "link";

    private static final String REGISTRATION_ID = "registrationId";

    private final SocialClientRegistrations registrations;
    private final PendingSocialLoginStore pendingStore;
    private final DefaultOAuth2AuthorizationRequestResolver delegate;
    private final PathPatternRequestMatcher matcher =
            PathPatternRequestMatcher.withDefaults()
                    .matcher(BASE_URI + "/{" + REGISTRATION_ID + "}");

    public SocialAuthorizationRequestResolver(
            SocialClientRegistrations registrations, PendingSocialLoginStore pendingStore) {
        this.registrations = registrations;
        this.pendingStore = pendingStore;
        this.delegate = new DefaultOAuth2AuthorizationRequestResolver(registrations, BASE_URI);
    }

    @Override
    public OAuth2AuthorizationRequest resolve(HttpServletRequest request) {
        if (!matcher.matches(request)) {
            return null;
        }
        String registrationId = matcher.matcher(request).getVariables().get(REGISTRATION_ID);
        return resolve(request, registrationId);
    }

    @Override
    public OAuth2AuthorizationRequest resolve(
            HttpServletRequest request, String clientRegistrationId) {
        Optional<SocialProviderAdapter> adapter = registrations.adapter(clientRegistrationId);
        if (adapter.isEmpty() || alreadyLoggedIn()) {
            return null;
        }

        OAuth2AuthorizationRequest authorizationRequest =
                delegate.resolve(request, clientRegistrationId);
        if (authorizationRequest == null) {
            return null;
        }

        if (LINK_INTENT.equals(request.getParameter("intent"))) {
            request.getSession(true).setAttribute(INTENT_ATTRIBUTE, LINK_INTENT);
        } else {
            if (request.getSession(false) != null) {
                request.getSession(false).removeAttribute(INTENT_ATTRIBUTE);
            }
            pendingStore.clear();
        }

        if (!"true".equals(request.getParameter("reprompt"))) {
            return authorizationRequest;
        }
        OAuth2AuthorizationRequest.Builder builder =
                OAuth2AuthorizationRequest.from(authorizationRequest);
        adapter.get().requestEmailConsentAgain(builder);
        return builder.build();
    }

    private static boolean alreadyLoggedIn() {
        return AuthenticatedPrincipal.userId(SecurityContextHolder.getContext().getAuthentication())
                .isPresent();
    }
}
