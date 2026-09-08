package com.ssafy.thorp.account.domain.model;

import java.time.Instant;

public class User {

    private final Long id;
    private final String email;

    private String passwordHash;

    private String nickname;

    private String phoneNumber;
    private String profileImageUrl;
    private UserStatus status;

    private boolean paperRankingEnabled;
    private boolean liveRankingEnabled;
    private boolean rankingDisplayNickname;

    private Instant lastLoginAt;
    private Instant createdAt;
    private Instant updatedAt;
    private Instant withdrawnAt;

    public User(
            Long id,
            String email,
            String passwordHash,
            String nickname,
            String phoneNumber,
            String profileImageUrl,
            UserStatus status,
            boolean paperRankingEnabled,
            boolean liveRankingEnabled,
            boolean rankingDisplayNickname,
            Instant lastLoginAt,
            Instant createdAt,
            Instant updatedAt,
            Instant withdrawnAt) {
        this.id = id;
        this.email = email;
        this.passwordHash = passwordHash;
        this.nickname = nickname;
        this.phoneNumber = phoneNumber;
        this.profileImageUrl = profileImageUrl;
        this.status = status;
        this.paperRankingEnabled = paperRankingEnabled;
        this.liveRankingEnabled = liveRankingEnabled;
        this.rankingDisplayNickname = rankingDisplayNickname;
        this.lastLoginAt = lastLoginAt;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.withdrawnAt = withdrawnAt;
    }

    public static User register(
            String email, String passwordHash, String nickname, String phoneNumber) {
        return new User(
                null,
                email,
                passwordHash,
                nickname,
                phoneNumber,
                null,
                UserStatus.PENDING_VERIFICATION,
                false,
                false,
                false,
                null,
                null,
                null,
                null);
    }

    public Long getId() {
        return id;
    }

    public String getEmail() {
        return email;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public String getNickname() {
        return nickname;
    }

    public String getPhoneNumber() {
        return phoneNumber;
    }

    public String getProfileImageUrl() {
        return profileImageUrl;
    }

    public UserStatus getStatus() {
        return status;
    }

    public boolean isPaperRankingEnabled() {
        return paperRankingEnabled;
    }

    public boolean isLiveRankingEnabled() {
        return liveRankingEnabled;
    }

    public boolean isRankingDisplayNickname() {
        return rankingDisplayNickname;
    }

    public Instant getLastLoginAt() {
        return lastLoginAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Instant getWithdrawnAt() {
        return withdrawnAt;
    }
}
