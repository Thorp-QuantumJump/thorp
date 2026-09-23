package com.ssafy.thorp.news.infrastructure.persistence;

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
     *
     * <p><b>없으면 만든다.</b> 갱신만 하면 첫 회차가 영영 시작되지 않는다 — 점유를 잡아야 회차가 돌고 회차가 끝나야 행이 생기는데, 행이 없으면 점유를 잡을 수
     * 없다. 마이그레이션이 seed하는 것은 정리 작업 행 하나뿐이고 수집 뉴스원의 행은 등록표가 바뀔 때마다 늘어나므로 seed로 메울 수도 없다(2026-09-15 첫
     * 실행에서 드러났다).
     *
     * <p>만드는 것과 잡는 것을 한 문장으로 둔다. 조회 뒤 없으면 넣는 방식은 인스턴스 둘이 동시에 뜰 때 둘 다 넣으려 해서 한쪽이 기본키 충돌로 죽는다. {@code
     * ON CONFLICT ... WHERE}는 이미 누가 잡고 있으면 0행을 돌려주므로 그 판정이 그대로 점유 실패가 된다.
     *
     * <p>JPQL이 아니라 native인 이유는 upsert가 JPQL에 없어서다. 상태는 문자열로 넘긴다 — native 경로에는 enum 변환이 걸리지 않는다.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
            value =
                    "INSERT INTO news_collection_state"
                            + " (source_key, status, retry_count, lease_owner, lease_until,"
                            + " last_attempt_at, created_at, updated_at)"
                            + " VALUES (:sourceKey, :running, 0, :owner, :leaseUntil, :now, NOW(),"
                            + " NOW())"
                            + " ON CONFLICT (source_key) DO UPDATE SET"
                            + " lease_owner = EXCLUDED.lease_owner,"
                            + " lease_until = EXCLUDED.lease_until,"
                            + " status = EXCLUDED.status,"
                            + " last_attempt_at = EXCLUDED.last_attempt_at,"
                            + " updated_at = NOW()"
                            + " WHERE news_collection_state.lease_until IS NULL"
                            + " OR news_collection_state.lease_until <= :now",
            nativeQuery = true)
    int tryAcquireLease(
            @Param("sourceKey") String sourceKey,
            @Param("owner") String owner,
            @Param("now") Instant now,
            @Param("leaseUntil") Instant leaseUntil,
            @Param("running") String running);

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
