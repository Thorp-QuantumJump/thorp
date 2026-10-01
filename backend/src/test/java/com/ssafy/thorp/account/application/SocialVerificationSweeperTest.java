package com.ssafy.thorp.account.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.ssafy.thorp.account.application.service.SocialVerificationSweeper;
import com.ssafy.thorp.account.domain.model.SocialLinkTarget;
import com.ssafy.thorp.account.domain.model.SocialVerification;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 버려진 소셜 확인 기록 정리(2026-10-01 남은 위험 보완). 하루 지난 행만 지우고 진행 중인 행은 건드리지 않는다. */
class SocialVerificationSweeperTest {

    private static final Instant NOW = Instant.parse("2026-10-02T03:00:00Z");

    @Test
    @DisplayName("하루가 지난 대기 상태 기록과 대상 계정 창만 지운다")
    void sweepsOnlyStaleRows() {
        InMemorySocialVerificationRepository verifications =
                new InMemorySocialVerificationRepository();
        InMemorySocialLinkTargetRepository targets = new InMemorySocialLinkTargetRepository();
        verifications.save(SocialVerification.open("stale", NOW.minus(Duration.ofDays(2))));
        verifications.save(SocialVerification.open("fresh", NOW.minus(Duration.ofMinutes(5))));
        targets.save(SocialLinkTarget.open(1L, NOW.minus(Duration.ofDays(2))));
        targets.save(SocialLinkTarget.open(2L, NOW.minus(Duration.ofMinutes(30))));

        int removed =
                new SocialVerificationSweeper(
                                verifications, targets, Clock.fixed(NOW, ZoneOffset.UTC))
                        .sweep();

        assertThat(removed).isEqualTo(2);
        assertThat(verifications.get("stale")).isNull();
        assertThat(verifications.get("fresh")).isNotNull();
        assertThat(targets.get(1L)).isNull();
        assertThat(targets.get(2L)).isNotNull();
    }
}
