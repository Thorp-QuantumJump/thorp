package com.ssafy.thorp.account.application.service;

import com.ssafy.thorp.account.application.repository.SocialLinkTargetRepository;
import com.ssafy.thorp.account.application.repository.SocialVerificationRepository;
import com.ssafy.thorp.account.domain.model.SocialLinkTarget;
import com.ssafy.thorp.account.domain.model.SocialVerification;
import com.ssafy.thorp.account.domain.model.SocialVerification.CodeTarget;
import java.time.Clock;
import java.util.function.Function;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 인증번호 발송과 시도 기록을 잠근 행 위에서 바꾼다.
 *
 * <p><b>트랜잭션을 따로 연다({@code REQUIRES_NEW}).</b> 시도 횟수는 대조가 틀려 바깥 요청이 실패해도 남아야 한다. 바깥 트랜잭션에 참여하면 그
 * 실패의 롤백이 방금 센 시도까지 되돌려 상한이 무의미해진다. 규칙 위반({@link SocialVerification.RejectedAttempt})도 커밋한 뒤 올린다 —
 * 다 쓴 번호를 지운 변경이 남아야 한다.
 */
@Service
public class SocialVerificationGuard {

    private final SocialVerificationRepository repository;
    private final SocialLinkTargetRepository targets;
    private final Clock clock;

    public SocialVerificationGuard(
            SocialVerificationRepository repository,
            SocialLinkTargetRepository targets,
            Clock clock) {
        this.repository = repository;
        this.targets = targets;
        this.clock = clock;
    }

    @Transactional(
            propagation = Propagation.REQUIRES_NEW,
            noRollbackFor = SocialVerification.RejectedAttempt.class)
    public void issue(String id, String codeHash, CodeTarget target) {
        change(
                id,
                verification -> {
                    verification.issue(codeHash, target, clock.instant());
                    return null;
                });
    }

    /** 번호 하나의 대조 자격을 얻고 대조할 hash를 돌려준다. 시도는 대조 전에 센다. */
    @Transactional(
            propagation = Propagation.REQUIRES_NEW,
            noRollbackFor = SocialVerification.RejectedAttempt.class)
    public String reserveCodeAttempt(String id, CodeTarget target) {
        return change(id, verification -> verification.reserveCodeAttempt(target, clock.instant()));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void consumeCode(String id) {
        change(
                id,
                verification -> {
                    verification.consumeCode();
                    return null;
                });
    }

    @Transactional(
            propagation = Propagation.REQUIRES_NEW,
            noRollbackFor = SocialVerification.RejectedAttempt.class)
    public void reservePasswordAttempt(String id) {
        change(
                id,
                verification -> {
                    verification.reservePasswordAttempt();
                    return null;
                });
    }

    /** 그 계정을 향한 인증번호·비밀번호 대조 한 번. 한 시간 창의 상한을 넘으면 막는다. 대조 전에 센다. */
    @Transactional(
            propagation = Propagation.REQUIRES_NEW,
            noRollbackFor = SocialVerification.RejectedAttempt.class)
    public void reserveTargetAttempt(Long userId) {
        changeTarget(userId, target -> target.reserveAttempt(clock.instant()));
    }

    /** 그 계정 이메일로 인증번호를 한 번 보낸다. 한 시간 창의 상한을 넘으면 막는다. */
    @Transactional(
            propagation = Propagation.REQUIRES_NEW,
            noRollbackFor = SocialVerification.RejectedAttempt.class)
    public void reserveTargetSend(Long userId) {
        changeTarget(userId, target -> target.reserveSend(clock.instant()));
    }

    /** 주인임을 확인했다. 그 계정을 향한 실패 누적을 지운다. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void clearTargetAttempts(Long userId) {
        changeTarget(userId, SocialLinkTarget::clearAttempts);
    }

    /** 가입·연결이 끝나 더 쓸 일이 없는 기록을 지운다. */
    @Transactional
    public void discard(String id) {
        repository.delete(id);
    }

    private void changeTarget(Long userId, java.util.function.Consumer<SocialLinkTarget> action) {
        SocialLinkTarget target = targets.lockOrOpen(userId, clock.instant());
        try {
            action.accept(target);
        } finally {
            targets.save(target);
        }
    }

    private <T> T change(String id, Function<SocialVerification, T> action) {
        SocialVerification verification = repository.lockOrOpen(id, clock.instant());
        try {
            return action.apply(verification);
        } finally {
            repository.save(verification);
        }
    }
}
