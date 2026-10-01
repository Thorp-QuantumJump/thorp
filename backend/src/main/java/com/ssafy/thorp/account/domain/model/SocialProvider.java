package com.ssafy.thorp.account.domain.model;

import java.util.Locale;
import java.util.Optional;

/**
 * 지원하는 소셜 로그인 제공자(spec 014 FR-020).
 *
 * <p>DB에는 enum 이름(대문자)을, 외부 경로·계약에는 {@link #registrationId()}(소문자)를 쓴다. 두 표기가 섞이지 않도록 변환은 여기에만 둔다.
 */
public enum SocialProvider {
    KAKAO,
    GOOGLE,
    NAVER;

    /** 외부 경로({@code /api/auth/oauth2/authorization/{provider}})와 계약의 {@code SocialProviderId}. */
    public String registrationId() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static Optional<SocialProvider> fromRegistrationId(String registrationId) {
        if (registrationId == null) {
            return Optional.empty();
        }
        for (SocialProvider provider : values()) {
            if (provider.registrationId().equals(registrationId)) {
                return Optional.of(provider);
            }
        }
        return Optional.empty();
    }
}
