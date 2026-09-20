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

/** {@code regime_link_use}의 JPA 매핑 (data-model §4a). */
@Entity
@Table(name = "regime_link_use")
public class RegimeLinkUseJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "regime_link_use_seq_gen")
    @SequenceGenerator(
            name = "regime_link_use_seq_gen",
            sequenceName = "regime_link_use_seq",
            allocationSize = 1)
    private Long id;

    @Column(nullable = false)
    private Long linkSearchId;

    @Column(nullable = false)
    private Long linkId;

    @Column(nullable = false, length = 8)
    private String role;

    @Column(nullable = false)
    private Long originRunId;

    private Long originRequestId;

    private Long createdRequestId;

    @Column(nullable = false)
    private Instant createdAt;

    protected RegimeLinkUseJpaEntity() {}

    public RegimeLinkUseJpaEntity(
            Long linkSearchId,
            Long linkId,
            String role,
            Long originRunId,
            Long originRequestId,
            Long createdRequestId) {
        this.linkSearchId = linkSearchId;
        this.linkId = linkId;
        this.role = role;
        this.originRunId = originRunId;
        this.originRequestId = originRequestId;
        this.createdRequestId = createdRequestId;
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

    public Long getLinkSearchId() {
        return linkSearchId;
    }

    public Long getLinkId() {
        return linkId;
    }

    public String getRole() {
        return role;
    }

    public Long getOriginRunId() {
        return originRunId;
    }

    public Long getOriginRequestId() {
        return originRequestId;
    }

    public Long getCreatedRequestId() {
        return createdRequestId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
