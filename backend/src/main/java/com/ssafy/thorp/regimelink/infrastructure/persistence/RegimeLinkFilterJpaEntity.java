package com.ssafy.thorp.regimelink.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Map;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** {@code regime_link_filter}의 JPA 매핑 (data-model §2). */
@Entity
@Table(name = "regime_link_filter")
public class RegimeLinkFilterJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "regime_link_filter_seq_gen")
    @SequenceGenerator(
            name = "regime_link_filter_seq_gen",
            sequenceName = "regime_link_filter_seq",
            allocationSize = 1)
    private Long id;

    @Column(nullable = false, unique = true, length = 64, columnDefinition = "bpchar")
    private String filterKey;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> definition;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> availableRange;

    @Column(nullable = false)
    private Instant createdAt;

    protected RegimeLinkFilterJpaEntity() {}

    public RegimeLinkFilterJpaEntity(
            String filterKey, Map<String, Object> definition, Map<String, Object> availableRange) {
        this.filterKey = filterKey;
        this.definition = definition;
        this.availableRange = availableRange;
    }

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    public Long getId() {
        return id;
    }

    public String getFilterKey() {
        return filterKey;
    }

    public Map<String, Object> getDefinition() {
        return definition;
    }

    public Map<String, Object> getAvailableRange() {
        return availableRange;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
