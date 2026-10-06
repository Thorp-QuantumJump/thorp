package com.ssafy.thorp.account.application.service;

import com.ssafy.thorp.account.application.repository.SocialLinkTargetRepository;
import com.ssafy.thorp.account.application.repository.SocialVerificationRepository;
import com.ssafy.thorp.account.domain.model.SocialLinkTarget;
import com.ssafy.thorp.account.domain.model.SocialVerification;
import com.ssafy.thorp.account.domain.model.SocialVerification.CodeTarget;
import java.time.Clock;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 인증번호 발송과 시도 기록을 잠근 행 위에서 바꾼다.
 *
 * <p><b>트랜잭션을 따로 연다({@code REQUIRES_NEW}).</b> 시도 횟수는 대조가 틀려 바깥 요청이 실패해도 남아야 한다. 바깥 트랜잭션에 참여하면 그
 * 실패의 롤백이 방금 센 시도까지 되돌려 상한이 무의미해진다. 규칙 위반({@link SocialVerification.RejectedAttempt})도 커밋한 뒤 올린다 —
 * 다 쓴 번호를 지운 변경이 남아야 한다.
 *
 * <p><b>연결 대상 계정이 걸린 동작은 한 트랜잭션에서 두 행을 함께 잠근다.</b> 대기 상태 기록 → 대상 계정 기록 순서로 잠그고, 두 쪽의 판정을 모두 통과한 뒤에만
 * 두 쪽의 숫자를 함께 저장한다. 따로 커밋하면 계정 단위로 막힌 요청이 대기 상태의 번호를 이미 바꿔 버리거나(메일은 안 갔는데 받은 번호가 무효), 대조할 수조차 없는
 * 요청(번호 없음·만료)이 계정의 시도를 먹는다(2026-10-01 code review).
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

    /** 새 번호를 단다. {@code linkTargetUserId}가 있으면 그 계정 이메일로 가는 번호라 계정 단위 발송 상한도 함께 본다. */
    @Transactional(
            propagation = Propagation.REQUIRES_NEW,
            noRollbackFor = SocialVerification.RejectedAttempt.class)
    public void issue(String id, String codeHash, CodeTarget target, Long linkTargetUserId) {
        Instant now = clock.instant();
        SocialVerification verification = repository.lockOrOpen(id, now);
        SocialLinkTarget account =
                linkTargetUserId == null ? null : targets.lockOrOpen(linkTargetUserId, now);
        // 대기 상태의 간격·횟수를 먼저 본다. 여기서 막히면 아무것도 바뀌지 않았다.
        verification.issue(codeHash, target, now);
        if (account != null) {
            // 계정 단위로 막히면 위의 변경은 저장하지 않는다 — 이미 받은 번호가 그대로 쓸 수 있게 남는다.
            account.reserveSend(now);
            targets.save(account);
        }
        repository.save(verification);
    }

    /**
     * 번호 하나의 대조 자격을 얻고 대조할 hash를 돌려준다. 시도는 대조 전에 센다.
     *
     * <p>대조할 수 있는 요청인지(번호 있음·만료 전·남은 횟수)를 먼저 보고, 그때만 계정 단위 시도를 센다.
     */
    @Transactional(
            propagation = Propagation.REQUIRES_NEW,
            noRollbackFor = SocialVerification.RejectedAttempt.class)
    public String reserveCodeAttempt(String id, CodeTarget target, Long linkTargetUserId) {
        Instant now = clock.instant();
        SocialVerification verification = repository.lockOrOpen(id, now);
        SocialLinkTarget account =
                linkTargetUserId == null ? null : targets.lockOrOpen(linkTargetUserId, now);
        String hash;
        try {
            hash = verification.reserveCodeAttempt(target, now);
        } catch (SocialVerification.RejectedAttempt rejected) {
            // 다 쓴 번호를 지운 변경은 남긴다. 계정 단위 시도는 세지 않는다.
            repository.save(verification);
            throw rejected;
        }
        if (account != null) {
            account.reserveAttempt(now);
            targets.save(account);
        }
        repository.save(verification);
        return hash;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void consumeCode(String id) {
        SocialVerification verification = repository.lockOrOpen(id, clock.instant());
        verification.consumeCode();
        repository.save(verification);
    }

    /** 연결 단계 비밀번호 한 번의 대조 자격. 대기 상태의 상한을 먼저 보고, 그때만 계정 단위 시도를 센다. */
    @Transactional(
            propagation = Propagation.REQUIRES_NEW,
            noRollbackFor = SocialVerification.RejectedAttempt.class)
    public void reservePasswordAttempt(String id, Long linkTargetUserId) {
        Instant now = clock.instant();
        SocialVerification verification = repository.lockOrOpen(id, now);
        SocialLinkTarget account = targets.lockOrOpen(linkTargetUserId, now);
        verification.reservePasswordAttempt();
        account.reserveAttempt(now);
        targets.save(account);
        repository.save(verification);
    }

    /** 주인임을 확인했다. 그 계정을 향한 실패 누적을 지운다. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void clearTargetAttempts(Long userId) {
        SocialLinkTarget account = targets.lockOrOpen(userId, clock.instant());
        account.clearAttempts();
        targets.save(account);
    }

    /** 가입·연결이 끝나 더 쓸 일이 없는 기록을 지운다. */
    @Transactional
    public void discard(String id) {
        repository.delete(id);
    }
}
