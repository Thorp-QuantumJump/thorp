package com.ssafy.thorp.account.infrastructure.oauth;

import com.ssafy.thorp.account.application.port.PublicSiteUrl;
import org.springframework.stereotype.Component;

/** {@link SocialAuthProperties#publicBaseUrl()}로 FE 화면 주소를 만든다. */
@Component
public class PublicSiteUrlProvider implements PublicSiteUrl {

    private final SocialAuthProperties properties;

    public PublicSiteUrlProvider(SocialAuthProperties properties) {
        this.properties = properties;
    }

    @Override
    public String of(String path) {
        return properties.publicBaseUrl() + path;
    }
}
