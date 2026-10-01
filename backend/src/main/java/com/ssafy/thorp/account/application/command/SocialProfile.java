package com.ssafy.thorp.account.application.command;

import com.ssafy.thorp.account.domain.model.SocialProvider;
import java.util.Objects;

/**
 * 제공자 응답을 제공자와 무관한 형태로 바꾼 값. 이후 판정은 제공자를 모른다(plan Provider integration).
 *
 * @param email 제공자가 준 이메일. 주지 않았으면 {@code null}이다(FR-022 거절).
 * @param nickname 제공자 닉네임. 없으면 {@code null}.
 */
public record SocialProfile(
        SocialProvider provider,
        String providerUserId,
        String email,
        EmailStatus emailStatus,
        String nickname) {

    /** 제공자가 알려준 이메일 확인 여부. 알려주지 않았으면 {@link #UNKNOWN}이다(네이버). */
    public enum EmailStatus {
        VERIFIED,
        UNVERIFIED,
        UNKNOWN
    }

    public SocialProfile {
        Objects.requireNonNull(provider, "provider");
        Objects.requireNonNull(emailStatus, "emailStatus");
        if (providerUserId == null || providerUserId.isBlank()) {
            throw new IllegalArgumentException("providerUserId must not be blank");
        }
    }

    public boolean hasEmail() {
        return email != null && !email.isBlank();
    }
}
