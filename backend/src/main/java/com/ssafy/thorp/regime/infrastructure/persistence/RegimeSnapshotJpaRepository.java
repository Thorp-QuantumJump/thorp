package com.ssafy.thorp.regime.infrastructure.persistence;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface RegimeSnapshotJpaRepository extends JpaRepository<RegimeSnapshotJpaEntity, Long> {

    /**
     * 그 (모드·분야·기준월)의 가장 큰 revision.
     *
     * <p>종합은 {@code field_id}가 NULL이라 {@code = :fieldId}만으로는 한 행도 고르지 못한다 — NULL 비교는 어떤 값과도 참이 되지
     * 않는다. 그 상태는 "종합 결과가 없다"와 생김새가 같아 조용히 404가 된다.
     */
    @Query(
            """
            select s from RegimeSnapshotJpaEntity s
            where s.mode = :mode
              and ((:fieldId is null and s.fieldId is null) or s.fieldId = :fieldId)
              and s.baselineMonth = :baselineMonth
            order by s.revision desc
            """)
    List<RegimeSnapshotJpaEntity> findLatest(
            @Param("mode") String mode,
            @Param("fieldId") String fieldId,
            @Param("baselineMonth") LocalDate baselineMonth,
            Limit limit);

    Optional<RegimeSnapshotJpaEntity> findBySnapshotKey(String snapshotKey);
}
