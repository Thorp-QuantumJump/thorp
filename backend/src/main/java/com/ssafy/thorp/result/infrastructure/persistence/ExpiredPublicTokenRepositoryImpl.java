package com.ssafy.thorp.result.infrastructure.persistence;

import com.ssafy.thorp.result.application.repository.ExpiredPublicTokenRepository;
import java.time.Instant;
import org.springframework.stereotype.Repository;

/** 공개 토큰 묘비 저장. 쓰기는 모두 네이티브 질의이고 호출자의 트랜잭션에 참여한다. */
@Repository
public class ExpiredPublicTokenRepositoryImpl implements ExpiredPublicTokenRepository {

    private final ExpiredPublicTokenJpaRepository jpaRepository;

    public ExpiredPublicTokenRepositoryImpl(ExpiredPublicTokenJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    public void record(String tokenHash, Instant expiredAt, Instant recordedAt) {
        jpaRepository.insertIfAbsent(tokenHash, expiredAt, recordedAt);
    }

    @Override
    public boolean exists(String tokenHash) {
        return jpaRepository.existsById(tokenHash);
    }

    @Override
    public int deleteExpiredBefore(Instant cutoff, int limit) {
        return jpaRepository.deleteExpiredBefore(cutoff, limit);
    }
}
