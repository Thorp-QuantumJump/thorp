package com.ssafy.thorp.regimelink.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Map;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * {@code regime_link_filter}의 JPA 매핑 (data-model §2).
 *
 * <p><b>읽기 전용이다.</b> 행을 넣는 것은 {@link RegimeLinkFilterRepositoryImpl}의 {@code ON CONFLICT DO
 * NOTHING} INSERT 뿐이다 — 같은 키를 동시에 저장할 때 제약 위반으로 트랜잭션이 되돌아가는 것을 막으려고 그렇게 두었다. 여기에 저장 경로를 다시 만들면 그
 * 보호를 우회하게 된다.
 */
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
