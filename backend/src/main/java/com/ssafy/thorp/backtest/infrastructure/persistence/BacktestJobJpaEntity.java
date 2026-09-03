package com.ssafy.thorp.backtest.infrastructure.persistence;

import com.ssafy.thorp.backtest.domain.model.BacktestStatus;
import com.ssafy.thorp.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;
import org.hibernate.annotations.ColumnDefault;

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

    /** 초기 자산(KRW). {@code ddl-auto=update}로 기존 행이 있는 테이블에 NOT NULL 컬럼을 추가할 수 있도록 DDL 기본값을 둔다. */
    @Column(nullable = false)
    @ColumnDefault("10000000")
    private long initialCash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private BacktestStatus status;

    protected BacktestJobJpaEntity() {}

    BacktestJobJpaEntity(
            Long id,
            String strategy,
            LocalDate startDate,
            LocalDate endDate,
            long initialCash,
            BacktestStatus status) {
        this.id = id;
        this.strategy = strategy;
        this.startDate = startDate;
        this.endDate = endDate;
        this.initialCash = initialCash;
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

    public long getInitialCash() {
        return initialCash;
    }

    public BacktestStatus getStatus() {
        return status;
    }
}
