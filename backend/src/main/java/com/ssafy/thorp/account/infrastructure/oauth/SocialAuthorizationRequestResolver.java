package com.ssafy.thorp.account.infrastructure.oauth;

import com.ssafy.thorp.account.application.AuthenticatedPrincipal;
import com.ssafy.thorp.account.application.port.PendingSocialLoginStore;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
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
 *   <li>{@code returnTo}가 있으면 로그인 뒤 돌아갈 자리로 세션에 적어 둔다(FR-011). 콜백이 기존 계정 로그인으로 끝나면 {@link
 *       SocialLoginSuccessHandler}가 경유 화면 없이 그 자리로 바로 보낸다. <b>검증은 여기서 한다</b> — 리다이렉트를 서버가 만들므로 사이트
 *       안 상대 경로만 받고, 인증 화면(FE {@code returnTarget.ts}와 같은 목록)은 순환이라 기본 복귀 위치로 바꾼다.
 * </ul>
 */
@Component
public class SocialAuthorizationRequestResolver implements OAuth2AuthorizationRequestResolver {

    static final String BASE_URI = "/auth/oauth2/authorization";
    static final String INTENT_ATTRIBUTE = "thorp.social.intent";
    static final String LINK_INTENT = "link";
    static final String RETURN_TO_ATTRIBUTE = "thorp.social.returnTo";

    /** 돌아갈 자리를 모를 때의 기본값. FE {@code returnTarget.ts}의 {@code DEFAULT_RETURN_TARGET}과 같다. */
    static final String DEFAULT_RETURN_TARGET = "/diagnosis/sectors";

    private static final List<String> AUTH_PATHS = List.of("/login", "/signup", "/auth/social");

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

        request.getSession(true)
                .setAttribute(
                        RETURN_TO_ATTRIBUTE,
                        normalizeReturnTarget(request.getParameter("returnTo")));

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

    /** 시작할 때 적어 둔 돌아갈 자리를 한 번만 읽고 지운다. 없으면 기본 복귀 위치다. */
    static String takeReturnTarget(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session == null) {
            return DEFAULT_RETURN_TARGET;
        }
        Object target = session.getAttribute(RETURN_TO_ATTRIBUTE);
        session.removeAttribute(RETURN_TO_ATTRIBUTE);
        return target instanceof String path ? path : DEFAULT_RETURN_TARGET;
    }

    /** 사이트 안 상대 경로(질의·조각 포함)만 통과시킨다. 외부 주소·스킴 상대 주소·인증 화면은 기본 복귀 위치다. */
    static String normalizeReturnTarget(String target) {
        if (target == null
                || !target.startsWith("/")
                || target.startsWith("//")
                || target.contains("\\")) {
            return DEFAULT_RETURN_TARGET;
        }
        URI uri;
        try {
            uri = new URI(target);
        } catch (URISyntaxException e) {
            return DEFAULT_RETURN_TARGET;
        }
        String path = uri.getPath();
        if (uri.getScheme() != null || uri.getAuthority() != null || path == null) {
            return DEFAULT_RETURN_TARGET;
        }
        boolean authPath =
                AUTH_PATHS.stream().anyMatch(p -> path.equals(p) || path.startsWith(p + "/"));
        return authPath ? DEFAULT_RETURN_TARGET : target;
    }

    private static boolean alreadyLoggedIn() {
        return AuthenticatedPrincipal.userId(SecurityContextHolder.getContext().getAuthentication())
                .isPresent();
    }
}
