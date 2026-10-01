package com.ssafy.thorp.account.application;

import com.ssafy.thorp.account.application.repository.SocialVerificationRepository;
import com.ssafy.thorp.account.domain.model.SocialVerification;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * 단위 테스트용 확인 기록 저장소. 잠금은 흉내 내지 않는다 — 동시 요청의 직렬화는 PostgreSQL 위의 통합 테스트가 본다.
 *
 * <p>저장할 때 값을 복사한다. 실제 저장소처럼 꺼낸 객체를 고쳐도 저장하기 전에는 기록이 바뀌지 않는다.
 */
public class InMemorySocialVerificationRepository implements SocialVerificationRepository {

    private final Map<String, SocialVerification> rows = new HashMap<>();

    @Override
    public SocialVerification lockOrOpen(String id, Instant now) {
        return copy(rows.computeIfAbsent(id, key -> SocialVerification.open(key, now)));
    }

    @Override
    public void save(SocialVerification verification) {
        rows.put(verification.getId(), copy(verification));
    }

    @Override
    public void delete(String id) {
        rows.remove(id);
    }

    public SocialVerification get(String id) {
        return rows.get(id);
    }

    private static SocialVerification copy(SocialVerification v) {
        return new SocialVerification(
                v.getId(),
                v.getCodeHash(),
                v.getCodeTarget(),
                v.getCodeExpiresAt(),
                v.getCodeAttempts(),
                v.getCodesSent(),
                v.getLastCodeSentAt(),
                v.getPasswordAttempts(),
                v.getCreatedAt());
    }
}
