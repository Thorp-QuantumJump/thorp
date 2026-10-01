package com.ssafy.thorp.account.application.repository;

import com.ssafy.thorp.account.domain.model.SocialLinkTarget;
import java.time.Instant;

/** 대상 계정 단위의 연결 확인 누적 기록. {@link #lockOrOpen}은 행을 잠근다 — 같은 계정을 향한 동시 시도가 여기서 줄을 선다. */
public interface SocialLinkTargetRepository {

    SocialLinkTarget lockOrOpen(Long userId, Instant now);

    void save(SocialLinkTarget target);

    /** 창이 끝난 지 오래된 기록을 지운다. 지운 행 수를 돌려준다. */
    int deleteWindowsStartedBefore(Instant cutoff);
}
