package com.ssafy.thorp.account.infrastructure.oauth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository;

/**
 * 제공자 access token을 어디에도 남기지 않는 저장소(FR-025, research D10).
 *
 * <p>Thorp는 사용자 정보를 한 번 읽은 뒤 제공자 API를 다시 부르지 않는다. 기본 저장소는 token을 세션에 넣어 PostgreSQL 세션 행에 30분 동안
 * 남긴다.
 */
public class DiscardingAuthorizedClientRepository implements OAuth2AuthorizedClientRepository {

    @Override
    public <T extends OAuth2AuthorizedClient> T loadAuthorizedClient(
            String clientRegistrationId, Authentication principal, HttpServletRequest request) {
        return null;
    }

    @Override
    public void saveAuthorizedClient(
            OAuth2AuthorizedClient authorizedClient,
            Authentication principal,
            HttpServletRequest request,
            HttpServletResponse response) {
        // 일부러 아무것도 하지 않는다.
    }

    @Override
    public void removeAuthorizedClient(
            String clientRegistrationId,
            Authentication principal,
            HttpServletRequest request,
            HttpServletResponse response) {
        // 저장한 것이 없다.
    }
}
