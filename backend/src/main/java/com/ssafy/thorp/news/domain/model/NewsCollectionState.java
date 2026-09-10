package com.ssafy.thorp.news.domain.model;

import com.ssafy.thorp.news.domain.vo.NewsCollectionStatus;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * 뉴스원별 수집 실행 상태와 수집 위치, 인스턴스 간 점유. 순수 도메인 모델이다.
 *
 * <p>{@code sourceKey}는 수집 뉴스원뿐 아니라 일일 정리 작업도 포함한다. 둘 다 같은 점유 규칙을 써서 인스턴스가 여러 개여도 한 번에 하나만
 * 실행된다(spec FR-022).
 *
 * <p><b>수집 위치는 저장이 확정된 뒤에만 전진한다.</b> 실패·부분 수집에서 위치를 밀면 그 구간의 기사가 영구히 누락된다.
 *
 * <p>{@code errorCategory}는 가림 정책을 통과한 분류 코드만 담는다. URL·인증 정보·외부 응답 원문을 담지 않는다(spec FR-021).
 */
public class NewsCollectionState {

    /** 정리 작업의 점유 키. 수집 뉴스원과 같은 테이블에서 경쟁한다. */
    public static final String RETENTION_CLEANUP_SOURCE_KEY = "retention-cleanup";

    /** GDELT 결과 상한 때문에 구간을 다 받지 못했을 때 남기는 분류 코드(spec FR-013). */
    public static final String RESULT_CAP_REACHED = "RESULT_CAP_REACHED";

    /** 사업 키가 다른데 외부 식별자가 겹쳤을 때 남기는 분류 코드(spec FR-025). */
    public static final String ID_COLLISION = "ID_COLLISION";

    private final String sourceKey;
    private final NewsCollectionStatus status;
    private final String cursor;
    private final Instant lastAttemptAt;
    private final Instant lastSuccessAt;
    private final Instant nextAttemptAt;
    private final int retryCount;
    private final String errorCategory;
    private final String leaseOwner;
    private final Instant leaseUntil;

    private NewsCollectionState(
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
        this.sourceKey = requireText(sourceKey, "sourceKey");
        this.status = Objects.requireNonNull(status, "status must not be null");
        this.cursor = cursor;
        this.lastAttemptAt = lastAttemptAt;
        this.lastSuccessAt = lastSuccessAt;
        this.nextAttemptAt = nextAttemptAt;
        if (retryCount < 0) {
            throw new IllegalArgumentException("retryCount는 0 이상이어야 한다: " + retryCount);
        }
        this.retryCount = retryCount;
        this.errorCategory = errorCategory;
        this.leaseOwner = leaseOwner;
        this.leaseUntil = leaseUntil;
    }

    /** 새 뉴스원의 초기 상태. */
    public static NewsCollectionState idle(String sourceKey) {
        return new NewsCollectionState(
                sourceKey, NewsCollectionStatus.IDLE, null, null, null, null, 0, null, null, null);
    }

    /** 저장된 행을 복원한다. */
    public static NewsCollectionState restore(
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
        return new NewsCollectionState(
                sourceKey,
                status,
                cursor,
                lastAttemptAt,
                lastSuccessAt,
                nextAttemptAt,
                retryCount,
                errorCategory,
                leaseOwner,
                leaseUntil);
    }

    /** 회차를 시작하며 점유를 잡는다. 점유는 TTL이 지나면 다른 인스턴스가 회수한다. */
    public NewsCollectionState claimed(String owner, Instant now, Duration leaseTtl) {
        requireText(owner, "owner");
        Objects.requireNonNull(now, "now must not be null");
        Objects.requireNonNull(leaseTtl, "leaseTtl must not be null");
        return new NewsCollectionState(
                sourceKey,
                NewsCollectionStatus.RUNNING,
                cursor,
                now,
                lastSuccessAt,
                nextAttemptAt,
                retryCount,
                errorCategory,
                owner,
                now.plus(leaseTtl));
    }

    /** 회차가 성공했다. 수집 위치와 마지막 성공 시각을 함께 전진시킨다. */
    public NewsCollectionState succeeded(String nextCursor, Instant now, Instant nextAttemptAt) {
        Objects.requireNonNull(now, "now must not be null");
        return new NewsCollectionState(
                sourceKey,
                NewsCollectionStatus.SUCCEEDED,
                nextCursor,
                now,
                now,
                nextAttemptAt,
                0,
                null,
                null,
                null);
    }

    /**
     * RSS가 {@code 304 Not Modified}를 줬다. 성공으로 기록해 마지막 성공 시각만 갱신하고 수집 위치와 기사 데이터는 그대로 둔다(spec
     * FR-013).
     */
    public NewsCollectionState notModified(Instant now, Instant nextAttemptAt) {
        Objects.requireNonNull(now, "now must not be null");
        return new NewsCollectionState(
                sourceKey,
                NewsCollectionStatus.SUCCEEDED,
                cursor,
                now,
                now,
                nextAttemptAt,
                0,
                null,
                null,
                null);
    }

    /**
     * 결과 상한 때문에 요청 구간을 다 받지 못했다(spec FR-013).
     *
     * <p>받은 기사는 저장하되 수집 위치를 전진시키지 않고 마지막 성공 시각도 갱신하지 않는다. 다음 회차가 같은 구간을 재시도하며, 부분 수집을 완전 수집으로 기록하지
     * 않는다.
     */
    public NewsCollectionState incomplete(Instant now, Instant nextAttemptAt) {
        Objects.requireNonNull(now, "now must not be null");
        return new NewsCollectionState(
                sourceKey,
                NewsCollectionStatus.INCOMPLETE,
                cursor,
                now,
                lastSuccessAt,
                nextAttemptAt,
                retryCount,
                RESULT_CAP_REACHED,
                null,
                null);
    }

    /** 회차가 실패했다. 수집 위치와 마지막 성공 시각을 건드리지 않는다. */
    public NewsCollectionState failed(
            NewsCollectionStatus failureStatus,
            String errorCategory,
            Instant now,
            Instant nextAttemptAt) {
        Objects.requireNonNull(failureStatus, "failureStatus must not be null");
        if (failureStatus.advancesLastSuccess()) {
            throw new IllegalArgumentException("실패 상태가 아니다: " + failureStatus);
        }
        return new NewsCollectionState(
                sourceKey,
                failureStatus,
                cursor,
                now,
                lastSuccessAt,
                nextAttemptAt,
                retryCount + 1,
                errorCategory,
                null,
                null);
    }

    /** 지금 이 뉴스원의 회차를 시작할 수 있는지. 점유가 없거나 만료됐고 다음 시도 시각이 지났어야 한다. */
    public boolean isDue(Instant now) {
        Objects.requireNonNull(now, "now must not be null");
        boolean leaseFree = leaseUntil == null || !leaseUntil.isAfter(now);
        boolean scheduled = nextAttemptAt == null || !nextAttemptAt.isAfter(now);
        return leaseFree && scheduled;
    }

    private static String requireText(String value, String field) {
        Objects.requireNonNull(value, field + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + "는 비어 있을 수 없다");
        }
        return value;
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
}
