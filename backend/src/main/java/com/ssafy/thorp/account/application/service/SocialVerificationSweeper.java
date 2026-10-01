package com.ssafy.thorp.account.application.service;

import com.ssafy.thorp.account.application.repository.SocialLinkTargetRepository;
import com.ssafy.thorp.account.application.repository.SocialVerificationRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 끝나지 않고 버려진 소셜 확인 기록을 지운다(2026-10-01 남은 위험 보완).
 *
 * <p>가입·연결이 끝나면 기록을 바로 지우지만, 제공자 로그인 뒤 그냥 떠난 대기 상태의 기록과 창이 지난 대상 계정 기록은 남는다. 대기 상태는 10분, 대상 계정 창은
 * 1시간이면 끝나므로 하루가 지난 행은 더 쓸 일이 없다. 남겨 두면 누가 언제 어느 계정에 연결을 시도했는지가 쌓인다.
 *
 * <p>{@code thorp.auth.social.sweep.enabled=false}로 끌 수 있다. 다른 스케줄러처럼 테스트는 끄지 않고 메서드를 직접 불러도 된다 —
 * 하루 지난 행만 지우므로 다른 테스트의 행을 건드리지 않는다.
 */
@Component
@ConditionalOnProperty(
        prefix = "thorp.auth.social.sweep",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true)
public class SocialVerificationSweeper {

    private static final Logger log = LoggerFactory.getLogger(SocialVerificationSweeper.class);

    /** 이보다 오래된 행을 지운다. 대기 상태(10분)·대상 계정 창(1시간)보다 넉넉하게 둔다. */
    static final Duration RETENTION = Duration.ofDays(1);

    private final SocialVerificationRepository verifications;
    private final SocialLinkTargetRepository targets;
    private final Clock clock;

    public SocialVerificationSweeper(
            SocialVerificationRepository verifications,
            SocialLinkTargetRepository targets,
            Clock clock) {
        this.verifications = verifications;
        this.targets = targets;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${thorp.auth.social.sweep.interval:PT1H}")
    @Transactional
    public int sweep() {
        Instant cutoff = clock.instant().minus(RETENTION);
        int removed =
                verifications.deleteCreatedBefore(cutoff)
                        + targets.deleteWindowsStartedBefore(cutoff);
        if (removed > 0) {
            log.info("Swept stale social verification rows: count={}", removed);
        }
        return removed;
    }
}
