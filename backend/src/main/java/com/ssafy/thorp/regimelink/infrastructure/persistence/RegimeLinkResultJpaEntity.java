package com.ssafy.thorp.regimelink.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * {@code regime_link_result}의 JPA 매핑 (data-model §5).
 *
 * <p>PK 가 곧 링크 id 다 — 계산 하나에 결과 하나이고, 링크가 지워지면 CASCADE 로 함께 사라진다.
 *
 * <p>수치는 전부 문자열 decimal 로 들어 있다. {@code NUMERIC} 으로 내리면 scale 을 임의로 정해야 하고 넘는 값이 조용히 반올림된다(004 결과
 * 저장과 같은 규칙).
 */
@Entity
@Table(name = "regime_link_result")
public class RegimeLinkResultJpaEntity {

    @Id private Long linkId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> window63;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> window21;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private List<Map<String, Object>> holdings;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private List<Map<String, Object>> trades;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> kospi;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> basis;

    @Column(nullable = false, length = 64, columnDefinition = "bpchar")
    private String inputDigest;

    @Column(nullable = false)
    private Instant computedAt;

    protected RegimeLinkResultJpaEntity() {}

    public RegimeLinkResultJpaEntity(
            Long linkId,
            Map<String, Object> window63,
            Map<String, Object> window21,
            List<Map<String, Object>> holdings,
            List<Map<String, Object>> trades,
            Map<String, Object> kospi,
            Map<String, Object> basis,
            String inputDigest,
            Instant computedAt) {
        this.linkId = linkId;
        this.window63 = window63;
        this.window21 = window21;
        this.holdings = holdings;
        this.trades = trades;
        this.kospi = kospi;
        this.basis = basis;
        this.inputDigest = inputDigest;
        this.computedAt = computedAt;
    }

    @PrePersist
    void onCreate() {
        if (computedAt == null) {
            computedAt = Instant.now();
        }
    }

    public Long getLinkId() {
        return linkId;
    }

    public Map<String, Object> getWindow63() {
        return window63;
    }

    public Map<String, Object> getWindow21() {
        return window21;
    }

    public List<Map<String, Object>> getHoldings() {
        return holdings;
    }

    public List<Map<String, Object>> getTrades() {
        return trades;
    }

    public Map<String, Object> getKospi() {
        return kospi;
    }

    public Map<String, Object> getBasis() {
        return basis;
    }

    public String getInputDigest() {
        return inputDigest;
    }

    public Instant getComputedAt() {
        return computedAt;
    }
}
