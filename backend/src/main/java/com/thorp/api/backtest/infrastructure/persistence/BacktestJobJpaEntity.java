package com.thorp.api.backtest.infrastructure.persistence;

import com.thorp.api.backtest.domain.model.BacktestStatus;
import com.thorp.api.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;

/** {@code backtest_job} 테이블 매핑. 도메인 모델과는 {@link BacktestJobMapper}로 변환한다. */
@Entity
@Table(name = "backtest_job")
public class BacktestJobJpaEntity extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String strategy;

    @Column(nullable = false)
    private LocalDate startDate;

    @Column(nullable = false)
    private LocalDate endDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private BacktestStatus status;

    protected BacktestJobJpaEntity() {}

    BacktestJobJpaEntity(
            Long id,
            String strategy,
            LocalDate startDate,
            LocalDate endDate,
            BacktestStatus status) {
        this.id = id;
        this.strategy = strategy;
        this.startDate = startDate;
        this.endDate = endDate;
        this.status = status;
    }

    public Long getId() {
        return id;
    }

    public String getStrategy() {
        return strategy;
    }

    public LocalDate getStartDate() {
        return startDate;
    }

    public LocalDate getEndDate() {
        return endDate;
    }

    public BacktestStatus getStatus() {
        return status;
    }
}
