package com.ssafy.thorp.news.infrastructure.persistence;

import com.ssafy.thorp.news.domain.vo.NewsCollectionStatus;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** 수집 상태와 점유. */
public interface CollectionStateJpaRepository
        extends JpaRepository<NewsCollectionStateJpaEntity, String> {

    @Query(
            "SELECT s FROM NewsCollectionStateJpaEntity s"
                    + " WHERE (s.leaseUntil IS NULL OR s.leaseUntil <= :now)"
                    + " AND (s.nextAttemptAt IS NULL OR s.nextAttemptAt <= :now)"
                    + " ORDER BY s.sourceKey ASC")
    List<NewsCollectionStateJpaEntity> findDue(@Param("now") Instant now);

    /**
     * 점유를 조건부로 잡는다. 이미 다른 인스턴스가 유효한 점유를 갖고 있으면 0을 돌려준다.
     *
     * <p>조건부 UPDATE 한 문장이라 인스턴스가 여럿이어도 하나만 성공한다(spec FR-022).
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
            "UPDATE NewsCollectionStateJpaEntity s"
                    + " SET s.leaseOwner = :owner, s.leaseUntil = :leaseUntil,"
                    + " s.status = :running, s.lastAttemptAt = :now"
                    + " WHERE s.sourceKey = :sourceKey"
                    + " AND (s.leaseUntil IS NULL OR s.leaseUntil <= :now)")
    int tryAcquireLease(
            @Param("sourceKey") String sourceKey,
            @Param("owner") String owner,
            @Param("now") Instant now,
            @Param("leaseUntil") Instant leaseUntil,
            @Param("running") NewsCollectionStatus running);

    /** 점유를 놓는다. 이미 만료돼 다른 인스턴스가 가져갔으면 아무것도 하지 않는다. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
            "UPDATE NewsCollectionStateJpaEntity s"
                    + " SET s.leaseOwner = NULL, s.leaseUntil = NULL"
                    + " WHERE s.sourceKey = :sourceKey AND s.leaseOwner = :owner")
    int releaseLease(@Param("sourceKey") String sourceKey, @Param("owner") String owner);

    /**
     * 마지막 갱신 시각(spec FR-014). 성공 이력이 없으면 빈 값이다.
     *
     * <p>{@code publicOnly}가 참이면 공개 노출 가능한 매체의 뉴스원만 센다. 미허가 매체의 신선도가 공개 화면에 새지 않게 한다.
     *
     * <p>매체와 뉴스원은 수집 키로 잇는다. 접두를 질의에 적어 넣지 않고 {@code NewsSourceKey.RSS_PREFIX}를 그대로 받는다. 리터럴을 복사하면
     * 키 형식이 바뀔 때 이 질의만 조용히 어긋나 수집이 성공해도 '아직 수집되지 않음'이 된다.
     */
    default Optional<Instant> findLatestSuccessAt(boolean publicOnly, String rssPrefix) {
        return findLatestSuccessAt(
                publicOnly, rssPrefix, com.ssafy.thorp.news.domain.vo.PermissionStatus.ALLOWED);
    }

    /**
     * 허가 상태를 값으로 받는 조회.
     *
     * <p>enum을 질의문에 패키지 경로째로 적지 않고 바인딩한다. 문자열로 박아 두면 패키지를 옮기는 순간 컴파일러가 아무 말도 하지 않고, 기동할 때에야 질의 파싱에서
     * 터진다.
     */
    @Query(
            "SELECT MAX(s.lastSuccessAt) FROM NewsCollectionStateJpaEntity s"
                    + " WHERE s.lastSuccessAt IS NOT NULL"
                    + " AND (:publicOnly = FALSE OR EXISTS ("
                    + "   SELECT 1 FROM NewsPublisherJpaEntity p"
                    + "   WHERE p.enabled = TRUE"
                    + "   AND p.permissionStatus = :allowed"
                    + "   AND s.sourceKey = CONCAT(:rssPrefix, p.publisherKey)))")
    Optional<Instant> findLatestSuccessAt(
            @Param("publicOnly") boolean publicOnly,
            @Param("rssPrefix") String rssPrefix,
            @Param("allowed") com.ssafy.thorp.news.domain.vo.PermissionStatus allowed);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM NewsCollectionStateJpaEntity s WHERE s.sourceKey = :sourceKey")
    Optional<NewsCollectionStateJpaEntity> findForUpdate(@Param("sourceKey") String sourceKey);
}
