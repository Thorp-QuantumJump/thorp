package com.ssafy.thorp.regime.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;

/** {@code regime_baseline_month}의 JPA 매핑. 화면이 고를 수 있는 기준월이다(data-model §3). */
@Entity
@Table(name = "regime_baseline_month")
public class RegimeBaselineMonthJpaEntity {

    @Id
    @Column(length = 32)
    private String scopeId;

    @Column(nullable = false)
    private LocalDate baselineMonth;

    @Column(nullable = false)
    private LocalDate earliestMonth;

    @Column(nullable = false, length = 40)
    private String registryVersion;

    @Column(nullable = false, length = 40)
    private String dataVersion;

    @Column(nullable = false)
    private Instant updatedAt;

    protected RegimeBaselineMonthJpaEntity() {}

    String getScopeId() {
        return scopeId;
    }

    LocalDate getBaselineMonth() {
        return baselineMonth;
    }

    LocalDate getEarliestMonth() {
        return earliestMonth;
    }

    String getRegistryVersion() {
        return registryVersion;
    }

    String getDataVersion() {
        return dataVersion;
    }
}
