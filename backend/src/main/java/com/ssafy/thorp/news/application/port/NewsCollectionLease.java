package com.ssafy.thorp.news.application.port;

import java.time.Instant;
import java.util.Optional;

/**
 * 인스턴스 간 실행 점유 경계(spec FR-022). 구현은 DB 조건부 갱신으로 한다.
 *
 * <p>수집 뉴스원과 일일 정리 작업이 같은 규칙을 쓴다. 서비스 인스턴스가 여러 개여도 같은 작업을 한 번에 하나만 실행한다.
 *
 * <p>점유는 만료 시각을 가지며, 잡은 인스턴스가 죽어도 만료되면 다른 인스턴스가 회수한다.
 */
public interface NewsCollectionLease {

    /**
     * 지금 실행할 수 있는 작업의 점유를 잡는다.
     *
     * @param sourceKey 수집 뉴스원 또는 정리 작업 키.
     * @param owner 점유를 잡는 인스턴스 식별자.
     * @param now 현재 시각.
     * @return 잡았으면 만료 시각, 이미 다른 인스턴스가 잡고 있으면 빈 값.
     */
    Optional<Instant> tryAcquire(String sourceKey, String owner, Instant now);

    /**
     * 점유를 놓는다. 이미 만료돼 다른 인스턴스가 가져갔다면 아무것도 하지 않는다.
     *
     * @param sourceKey 점유 키.
     * @param owner 놓는 인스턴스 식별자.
     */
    void release(String sourceKey, String owner);
}
