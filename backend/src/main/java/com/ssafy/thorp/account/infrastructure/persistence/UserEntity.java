package com.ssafy.thorp.account.infrastructure.persistence;

import com.ssafy.thorp.account.domain.model.UserStatus;
import jakarta.persistence.*;

import java.time.Instant;

@Entity
@Table(name="users")
public class UserEntity {

    @Id
    @GeneratedValue(strategy= GenerationType.IDENTITY)
    private Long id;

    @Column(name = "email", nullable = false, unique = true, length = 255)
    private String email;

    @Column(name= "password_hash", nullable = false, unique = false, length = 255)
    private String passwordHash;

    @Column(name = "nickname", nullable = false, length = 100)
    private String nickname;

    @Column(name = "phone_number", nullable = false, unique = true, length = 20)
    private String phoneNumber;

    @Column(name = "profile_image_url", columnDefinition = "TEXT")
    private String profileImageUrl;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private UserStatus status;

    @Column(name = "paper_ranking_enabled", nullable = false)
    private boolean paperRankingEnabled;

    @Column(name = "live_ranking_enabled", nullable = false)
    private boolean liveRankingEnabled;

    @Column(name = "ranking_display_nickname", nullable = false)
    private boolean rankingDisplayNickname;

    @Column(name = "last_login_at")
    private Instant lastLoginAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "withdrawn_at")
    private Instant withdrawnAt;

    protected UserEntity() { //jpa용 기본 생성자
    }

    UserEntity(
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
        Instant withdrawnAt
    ) {
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

    @PrePersist
    private void onCreate() {
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    private void onUpdate() {
        updatedAt = Instant.now();
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
