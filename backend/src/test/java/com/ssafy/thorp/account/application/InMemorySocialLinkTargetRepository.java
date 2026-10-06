package com.ssafy.thorp.account.application;

import com.ssafy.thorp.account.application.repository.SocialLinkTargetRepository;
import com.ssafy.thorp.account.domain.model.SocialLinkTarget;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/** 단위 테스트용 대상 계정 누적 기록. 잠금은 흉내 내지 않는다 — 동시 요청은 PostgreSQL 위 통합 테스트가 본다. */
public class InMemorySocialLinkTargetRepository implements SocialLinkTargetRepository {

    private final Map<Long, SocialLinkTarget> rows = new HashMap<>();

    @Override
    public SocialLinkTarget lockOrOpen(Long userId, Instant now) {
        SocialLinkTarget row = rows.computeIfAbsent(userId, id -> SocialLinkTarget.open(id, now));
        return new SocialLinkTarget(
                row.getUserId(), row.getWindowStartedAt(), row.getAttempts(), row.getCodesSent());
    }

    @Override
    public void save(SocialLinkTarget target) {
        rows.put(
                target.getUserId(),
                new SocialLinkTarget(
                        target.getUserId(),
                        target.getWindowStartedAt(),
                        target.getAttempts(),
                        target.getCodesSent()));
    }

    @Override
    public int deleteWindowsStartedBefore(Instant cutoff) {
        int before = rows.size();
        rows.values().removeIf(row -> row.getWindowStartedAt().isBefore(cutoff));
        return before - rows.size();
    }

    public SocialLinkTarget get(Long userId) {
        return rows.get(userId);
    }
}
