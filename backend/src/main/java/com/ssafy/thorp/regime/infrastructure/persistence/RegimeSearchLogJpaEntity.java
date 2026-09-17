package com.ssafy.thorp.regime.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * {@code regime_search_log}의 JPA 매핑. <b>010은 읽기만 한다</b> — 행을 만드는 것은 006·014의 몫이다(spec FR-032).
 *
 * <p>사용자 식별자가 있는 유일한 표다. 사전 계산 결과는 여러 사용자가 공유하는 자원이라 snapshot·case에는 사용자를 가리키는 컬럼이 없고 앞으로도 두지
 * 않는다(FR-036, SC-013).
 *
 * <p>010이 이 표를 보는 이유는 하나뿐이다 — <b>철회된 항목을 가려내기 위해서</b>. 정리(FR-029)는 기록이 가리키는 항목을 남기므로, 기록이 있는데
 * {@code snapshotId}가 NULL인 것은 철회(FR-035)뿐이다.
 */
@Entity
@Table(name = "regime_search_log")
public class RegimeSearchLogJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long ownerUserId;

    private Long snapshotId;

    @Column(nullable = false, length = 200)
    private String snapshotKey;

    @Column(nullable = false, length = 24)
    private String entryPath;

    @Column(nullable = false, length = 16)
    private String baselineOrigin;

    @Column(nullable = false)
    private Instant viewedAt;

    protected RegimeSearchLogJpaEntity() {}

    Long getSnapshotId() {
        return snapshotId;
    }

    String getSnapshotKey() {
        return snapshotKey;
    }
}
