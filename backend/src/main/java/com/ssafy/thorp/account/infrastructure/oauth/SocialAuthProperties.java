package com.ssafy.thorp.account.infrastructure.oauth;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 소셜 로그인 설정({@code thorp.auth.social.*}).
 *
 * <p><b>자격 증명은 환경 변수로만 넣는다.</b> client id와 secret이 둘 다 있는 제공자만 켜진다. 없는 제공자는 시작 경로도 버튼도 없다 — 개발 PC나
 * CI에서 자격 증명 없이도 앱이 뜬다.
 *
 * <p>{@code spring.security.oauth2.client.*}를 쓰지 않는 이유: 그 설정은 값이 비면 기동이 실패하고, 등록이 하나도 없으면 저장소 빈이
 * 생기지 않아 {@code oauth2Login()}을 붙일 수 없다.
 *
 * @param publicBaseUrl 브라우저가 보는 서비스 주소. 제공자에게 넘기는 redirect URI를 이 값으로 만든다. 로컬 Vite proxy는 {@code
 *     Host}를 백엔드 주소로 바꾸므로 요청 주소로 추정하지 않는다(plan Provider integration).
 */
@ConfigurationProperties(prefix = "thorp.auth.social")
public record SocialAuthProperties(
        String publicBaseUrl, Client kakao, Client google, Client naver) {

    public SocialAuthProperties {
        // 비어 있으면 비워 둔다. 로컬 기본값은 application.yml이 준다. 운영에서 값을 빠뜨렸는데 로컬 주소로 메워 버리면 제공자가 모든 로그인을
        // redirect_uri 불일치로 거부하는데도 기동은 조용히 성공한다 — 제공자가 켜진 채 비어 있으면 SocialClientRegistrations가 기동을
        // 멈춘다.
        publicBaseUrl =
                publicBaseUrl == null || publicBaseUrl.isBlank()
                        ? ""
                        : stripTrailingSlash(publicBaseUrl.strip());
        kakao = kakao == null ? Client.NONE : kakao;
        google = google == null ? Client.NONE : google;
        naver = naver == null ? Client.NONE : naver;
    }

    public boolean hasPublicBaseUrl() {
        return !publicBaseUrl.isEmpty();
    }

    /** 제공자에게 등록할 redirect URI. 외부 경로라서 {@code /api}가 붙는다. */
    public String redirectUriTemplate() {
        return publicBaseUrl + "/api/auth/oauth2/callback/{registrationId}";
    }

    public record Client(String clientId, String clientSecret) {

        static final Client NONE = new Client(null, null);

        public boolean configured() {
            return clientId != null
                    && !clientId.isBlank()
                    && clientSecret != null
                    && !clientSecret.isBlank();
        }
    }

    private static String stripTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
