package com.ssafy.thorp.account.domain.model;

import java.time.Instant;

public class User {

    /** 하이픈·공백 없는 국내 휴대폰 번호(FR-001). 요청 검증과 서비스 검사가 같은 식을 쓴다. */
    public static final String PHONE_NUMBER_REGEX = "^01[016789]\\d{7,8}$";

    public static final String PHONE_NUMBER_MESSAGE = "국내 휴대폰 번호 형식이어야 합니다. 예: 01012345678";

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

    /** 가입 시점에 이미 이메일 인증을 마쳤으므로 바로 활성 상태로 만든다. */
    public static User register(
            String email, String passwordHash, String nickname, String phoneNumber) {
        return new User(
                null,
                email,
                passwordHash,
                nickname,
                phoneNumber,
                null,
                UserStatus.ACTIVE,
                false,
                false,
                false,
                null,
                null,
                null,
                null);
    }

    /**
     * 소셜 계정으로 가입한다(spec 014 FR-023). 비밀번호는 없다.
     *
     * <p>이메일은 제공자가 확인했거나 Thorp 인증번호로 확인한 값이라 이메일 가입과 같이 바로 활성 상태다.
     */
    public static User registerSocial(String email, String nickname, String phoneNumber) {
        return register(email, null, nickname, phoneNumber);
    }

    /** 이메일·비밀번호로 로그인할 수 있는 계정인가. 소셜로만 가입한 계정은 {@code false}다. */
    public boolean hasPassword() {
        return passwordHash != null && !passwordHash.isEmpty();
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
