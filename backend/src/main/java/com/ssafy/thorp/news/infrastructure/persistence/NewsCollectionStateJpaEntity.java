package com.ssafy.thorp.news.infrastructure.persistence;

import com.ssafy.thorp.common.persistence.BaseEntity;
import com.ssafy.thorp.news.domain.vo.NewsCollectionStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * {@code news_collection_state} 매핑. 뉴스원별 실행 상태와 수집 위치, 인스턴스 간 점유를 담는다.
 *
 * <p>{@code sourceKey}는 수집 뉴스원뿐 아니라 일일 정리 작업도 포함한다. 둘 다 같은 점유 규칙을 쓴다(spec FR-022).
 *
 * <p>컬럼 이름이 {@code cursor_value}인 것은 {@code cursor}가 PostgreSQL 예약어이기 때문이다.
 *
 * <p>{@code errorCategory}는 가림 정책을 통과한 분류 코드만 담는다. URL·인증 정보·응답 원문을 담지 않는다(spec FR-021).
 */
@Entity
@Table(name = "news_collection_state")
public class NewsCollectionStateJpaEntity extends BaseEntity {

    @Id
    @Column(length = 128)
    private String sourceKey;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private NewsCollectionStatus status;

    @Column(name = "cursor_value", columnDefinition = "text")
    private String cursor;

    private Instant lastAttemptAt;

    private Instant lastSuccessAt;

    private Instant nextAttemptAt;

    @Column(nullable = false)
    private int retryCount;

    @Column(length = 64)
    private String errorCategory;

    @Column(length = 128)
    private String leaseOwner;

    private Instant leaseUntil;

    protected NewsCollectionStateJpaEntity() {}

    NewsCollectionStateJpaEntity(
            String sourceKey,
            NewsCollectionStatus status,
            String cursor,
            Instant lastAttemptAt,
            Instant lastSuccessAt,
            Instant nextAttemptAt,
            int retryCount,
            String errorCategory,
            String leaseOwner,
            Instant leaseUntil) {
        this.sourceKey = sourceKey;
        this.status = status;
        this.cursor = cursor;
        this.lastAttemptAt = lastAttemptAt;
        this.lastSuccessAt = lastSuccessAt;
        this.nextAttemptAt = nextAttemptAt;
        this.retryCount = retryCount;
        this.errorCategory = errorCategory;
        this.leaseOwner = leaseOwner;
        this.leaseUntil = leaseUntil;
    }

    public String getSourceKey() {
        return sourceKey;
    }

    public NewsCollectionStatus getStatus() {
        return status;
    }

    public String getCursor() {
        return cursor;
    }

    public Instant getLastAttemptAt() {
        return lastAttemptAt;
    }

    public Instant getLastSuccessAt() {
        return lastSuccessAt;
    }

    public Instant getNextAttemptAt() {
        return nextAttemptAt;
    }

    public int getRetryCount() {
        return retryCount;
    }

    public String getErrorCategory() {
        return errorCategory;
    }

    public String getLeaseOwner() {
        return leaseOwner;
    }

    public Instant getLeaseUntil() {
        return leaseUntil;
    }

    /** 도메인이 계산한 다음 상태를 그대로 반영한다. 수집 위치 전진 여부도 도메인이 이미 판단했다. */
    void apply(
            NewsCollectionStatus status,
            String cursor,
            Instant lastAttemptAt,
            Instant lastSuccessAt,
            Instant nextAttemptAt,
            int retryCount,
            String errorCategory,
            String leaseOwner,
            Instant leaseUntil) {
        this.status = status;
        this.cursor = cursor;
        this.lastAttemptAt = lastAttemptAt;
        this.lastSuccessAt = lastSuccessAt;
        this.nextAttemptAt = nextAttemptAt;
        this.retryCount = retryCount;
        this.errorCategory = errorCategory;
        this.leaseOwner = leaseOwner;
        this.leaseUntil = leaseUntil;
    }
}
