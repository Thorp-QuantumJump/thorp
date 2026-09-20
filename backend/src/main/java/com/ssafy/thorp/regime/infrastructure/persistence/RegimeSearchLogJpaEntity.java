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

    /**
     * 행 하나를 만든다. <b>부르는 쪽은 006이다</b> — 010은 이 표를 읽기만 한다(FR-032).
     *
     * <p>{@code entryPath}·{@code baselineOrigin}을 문자열로 받는 이유: 값의 계약은 스키마의 {@code
     * ck_regime_log_entry_path}·{@code ck_regime_log_baseline_origin}이고, 010은 그 enum을 두지 않았다. 행을 쓰는
     * 쪽이 자기 타입으로 갖고 여기에 이름만 넘긴다.
     *
     * @param snapshotId 고정할 revision. 철회(FR-035)로 사라지면 NULL이 되고 {@code snapshotKey} 사본으로 무엇을 봤는지는
     *     남는다
     */
    public static RegimeSearchLogJpaEntity create(
            long ownerUserId,
            Long snapshotId,
            String snapshotKey,
            String entryPath,
            String baselineOrigin,
            Instant viewedAt) {
        RegimeSearchLogJpaEntity entity = new RegimeSearchLogJpaEntity();
        entity.ownerUserId = ownerUserId;
        entity.snapshotId = snapshotId;
        entity.snapshotKey = snapshotKey;
        entity.entryPath = entryPath;
        entity.baselineOrigin = baselineOrigin;
        entity.viewedAt = viewedAt;
        return entity;
    }

    public Long getId() {
        return id;
    }

    Long getSnapshotId() {
        return snapshotId;
    }

    String getSnapshotKey() {
        return snapshotKey;
    }
}
