package com.ssafy.thorp.news.infrastructure.persistence;

import com.ssafy.thorp.news.application.port.NewsCollectionLease;
import com.ssafy.thorp.news.domain.vo.NewsCollectionStatus;
import com.ssafy.thorp.news.infrastructure.config.NewsProperties;
import java.time.Instant;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link NewsCollectionLease} 구현. DB 조건부 갱신으로 인스턴스 간 점유를 잡는다(spec FR-022).
 *
 * <p>조건부 UPDATE 한 문장이라 인스턴스가 여럿이어도 하나만 성공한다. 점유는 만료 시각을 가지며, 잡은 인스턴스가 죽어도 만료되면 다른 인스턴스가 회수한다.
 *
 * <p>수집 뉴스원과 일일 정리 작업이 같은 규칙을 쓴다.
 */
@Component
public class DatabaseNewsCollectionLease implements NewsCollectionLease {

    private final CollectionStateJpaRepository jpaRepository;
    private final NewsProperties properties;

    public DatabaseNewsCollectionLease(
            CollectionStateJpaRepository jpaRepository, NewsProperties properties) {
        this.jpaRepository = jpaRepository;
        this.properties = properties;
    }

    @Override
    @Transactional
    public Optional<Instant> tryAcquire(String sourceKey, String owner, Instant now) {
        Instant leaseUntil = now.plus(properties.leaseTtl());
        int updated =
                jpaRepository.tryAcquireLease(
                        sourceKey, owner, now, leaseUntil, NewsCollectionStatus.RUNNING.name());
        return updated == 1 ? Optional.of(leaseUntil) : Optional.empty();
    }

    @Override
    @Transactional
    public void release(String sourceKey, String owner) {
        jpaRepository.releaseLease(sourceKey, owner);
    }
}
