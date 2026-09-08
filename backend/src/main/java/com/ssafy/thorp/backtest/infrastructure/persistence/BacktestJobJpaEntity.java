package com.ssafy.thorp.backtest.infrastructure.persistence;

import com.ssafy.thorp.backtest.domain.model.BacktestStatus;
import com.ssafy.thorp.backtest.domain.vo.BacktestId;
import com.ssafy.thorp.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.time.LocalDate;

/**
 * {@code backtest_job} 테이블 매핑. 도메인 모델과는 {@link BacktestJobMapper}로 변환한다.
 *
 * <p>PK가 SEQUENCE라 {@code persist()} 시점에 값이 나온다. 그 PK로 {@code backtest_id}를 INSERT 전에 채우므로 컬럼을 NOT
 * NULL로 둘 수 있고 사후 UPDATE가 없다(구현 설계 R1).
 */
@Entity
@Table(name = "backtest_job")
public class BacktestJobJpaEntity extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "backtest_job_seq_generator")
    @SequenceGenerator(
            name = "backtest_job_seq_generator",
            sequenceName = "backtest_job_seq",
            allocationSize = 1)
    private Long id;

    @Column(nullable = false, unique = true, length = 32)
    private String backtestId;

    @Column(nullable = false)
    private String strategy;

    @Column(nullable = false)
    private LocalDate startDate;

    @Column(nullable = false)
    private LocalDate endDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private BacktestStatus status;

    /** {@code backtestId}에 쓸 날짜 접두. 저장 전 한 번 예약하고 PK와의 결합은 {@code @PrePersist}가 한다. */
    @Transient private LocalDate backtestIdDate;

    protected BacktestJobJpaEntity() {}

    BacktestJobJpaEntity(
            Long id,
            String backtestId,
            String strategy,
            LocalDate startDate,
            LocalDate endDate,
            BacktestStatus status) {
        this.id = id;
        this.backtestId = backtestId;
        this.strategy = strategy;
        this.startDate = startDate;
        this.endDate = endDate;
        this.status = status;
    }

    /** 저장 직전 한 번만 외부 식별자를 예약한다. 이미 값이 있으면 거부한다. */
    void assignBacktestIdOn(LocalDate date) {
        if (this.backtestId != null) {
            throw new IllegalStateException("backtestId already assigned: " + this.backtestId);
        }
        this.backtestIdDate = date;
    }

    @PrePersist
    void fillBacktestId() {
        if (backtestId == null) {
            backtestId = BacktestId.of(backtestIdDate, id).value();
        }
    }

    public Long getId() {
        return id;
    }

    public String getBacktestId() {
        return backtestId;
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
