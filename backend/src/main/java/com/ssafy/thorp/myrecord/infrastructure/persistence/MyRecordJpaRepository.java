package com.ssafy.thorp.myrecord.infrastructure.persistence;

import com.ssafy.thorp.myrecord.domain.model.RecordKind;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * {@code my_record} 스프링 데이터 저장소.
 *
 * <p>찾는 질의가 모두 {@code deletedAt is null}과 {@code isCopy = false}를 건다. 지운 항목이 재사용 판정(FR-009)이나 상한
 * 계수(FR-018)에 되살아나면 사용자에게는 "지웠는데 새로 만들어지지 않는" 모습으로 드러난다. 같은 조건을 부분 유니크 인덱스도 걸고 있어 둘이 어긋나면 INSERT가
 * 막힌다.
 */
interface MyRecordJpaRepository extends JpaRepository<MyRecordJpaEntity, Long> {

    Optional<MyRecordJpaEntity> findByRecordId(String recordId);

    @Query(
            """
            select r from MyRecordJpaEntity r
            where r.ownerId = :ownerId
              and r.analysisRunId = :runId
              and r.deletedAt is null
              and r.isCopy = false
            """)
    Optional<MyRecordJpaEntity> findActiveByOwnerAndRun(
            @Param("ownerId") long ownerId, @Param("runId") long runId);

    @Query(
            """
            select r from MyRecordJpaEntity r
            where r.ownerId = :ownerId
              and r.paramSearchId = :paramSearchId
              and r.deletedAt is null
              and r.isCopy = false
            """)
    Optional<MyRecordJpaEntity> findActiveByOwnerAndParamSearch(
            @Param("ownerId") long ownerId, @Param("paramSearchId") long paramSearchId);

    @Query(
            """
            select r from MyRecordJpaEntity r
            where r.ownerId = :ownerId
              and r.regimeLogId = :regimeLogId
              and r.deletedAt is null
              and r.isCopy = false
            """)
    Optional<MyRecordJpaEntity> findActiveByOwnerAndRegimeLog(
            @Param("ownerId") long ownerId, @Param("regimeLogId") long regimeLogId);

    @Query(
            """
            select count(r) from MyRecordJpaEntity r
            where r.ownerId = :ownerId
              and r.kind = :kind
              and r.deletedAt is null
            """)
    long countActiveByOwnerAndKind(@Param("ownerId") long ownerId, @Param("kind") RecordKind kind);
}
