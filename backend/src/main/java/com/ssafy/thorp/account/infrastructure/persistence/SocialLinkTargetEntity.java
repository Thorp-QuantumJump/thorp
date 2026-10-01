package com.ssafy.thorp.account.infrastructure.persistence;

import com.ssafy.thorp.account.domain.model.SocialLinkTarget;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** {@code social_link_targets}. 마이그레이션은 {@code V20261001170000__social_link_targets.sql}. */
@Entity
@Table(name = "social_link_targets")
public class SocialLinkTargetEntity {

    @Id
    @Column(name = "user_id")
    private Long userId;

    @Column(name = "window_started_at", nullable = false)
    private Instant windowStartedAt;

    @Column(name = "attempts", nullable = false)
    private int attempts;

    @Column(name = "codes_sent", nullable = false)
    private int codesSent;

    protected SocialLinkTargetEntity() {}

    SocialLinkTarget toDomain() {
        return new SocialLinkTarget(userId, windowStartedAt, attempts, codesSent);
    }

    void apply(SocialLinkTarget target) {
        this.windowStartedAt = target.getWindowStartedAt();
        this.attempts = target.getAttempts();
        this.codesSent = target.getCodesSent();
    }
}
