package com.ssafy.thorp.account.application;

import com.ssafy.thorp.account.application.command.SocialProfile;
import com.ssafy.thorp.account.application.command.SocialProfile.EmailStatus;
import com.ssafy.thorp.account.domain.model.SocialAccount;
import com.ssafy.thorp.account.domain.model.SocialProvider;
import com.ssafy.thorp.account.domain.model.User;
import com.ssafy.thorp.account.domain.model.UserStatus;
import java.time.Instant;

/** 소셜 로그인 단위 테스트가 함께 쓰는 값. */
final class SocialFixtures {

    static final Instant NOW = Instant.parse("2026-10-01T03:00:00Z");
    static final String PROVIDER_USER_ID = "4100000001";

    private SocialFixtures() {}

    static SocialProfile profile(SocialProvider provider, String email, EmailStatus status) {
        return new SocialProfile(provider, PROVIDER_USER_ID, email, status, "소셜회원");
    }

    static User user(Long id, String email, UserStatus status, String passwordHash) {
        return new User(
                id,
                email,
                passwordHash,
                "기존회원",
                "01012345678",
                null,
                status,
                false,
                false,
                false,
                null,
                NOW,
                NOW,
                null);
    }

    static User activeUser(Long id, String email) {
        return user(id, email, UserStatus.ACTIVE, "hash");
    }

    static SocialAccount linked(Long userId, SocialProvider provider, String providerUserId) {
        return new SocialAccount(1L, userId, provider, providerUserId, NOW);
    }
}
