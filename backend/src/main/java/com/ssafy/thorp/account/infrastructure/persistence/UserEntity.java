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

    protected UserEntity() {
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
}
