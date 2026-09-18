package com.ssafy.thorp.result.application.repository;

import java.time.Instant;

/**
 * 만료로 지운 공개 토큰의 묘비 저장소(spec 005 T087). 구현은 {@code infrastructure.persistence}에 둔다.
 *
 * <p><b>토큰 원문을 받지 않는다.</b> 호출자가 해시로 바꿔 넘긴다 — 원문이 이 경계를 넘지 않아야 저장소 구현이나 로그로 새지 않는다.
 *
 * <p>이 저장소만 {@code result}에 두는 이유: 요청·토큰은 004의 {@code backtest_request}에 있어 {@code backtest}가 저장을
 * 소유하지만, 묘비는 공개 열람 경계에만 뜻이 있는 005의 기록이다. {@code backtest}가 이것을 알 필요가 없다(ArchUnit 규칙 9).
 */
public interface ExpiredPublicTokenRepository {

    /** 묘비를 남긴다. 같은 해시가 이미 있으면 아무것도 하지 않는다 — 정리 작업이 두 인스턴스에서 겹쳐 돌아도 실패하지 않는다. */
    void record(String tokenHash, Instant expiredAt, Instant recordedAt);

    boolean exists(String tokenHash);

    /**
     * 만료 시각이 {@code cutoff} 이전인 묘비를 오래된 것부터 {@code limit}건 지운다.
     *
     * @return 지운 수
     */
    int deleteExpiredBefore(Instant cutoff, int limit);
}
