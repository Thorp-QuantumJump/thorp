package com.thorp.api.backtest.domain.model;

import com.thorp.api.backtest.domain.vo.BacktestPeriod;
import java.util.Objects;

/**
 * 백테스트 작업 단위. 순수 도메인 모델이며 JPA Entity({@code BacktestJobJpaEntity})와 분리한다.
 *
 * <p>{@code id}는 저장 전에는 null이다.
 */
public class BacktestJob {

    private final Long id;
    private final String strategy;
    private final BacktestPeriod period;
    private final BacktestStatus status;

    private BacktestJob(Long id, String strategy, BacktestPeriod period, BacktestStatus status) {
        this.id = id;
        this.strategy = Objects.requireNonNull(strategy, "strategy must not be null");
        this.period = Objects.requireNonNull(period, "period must not be null");
        this.status = Objects.requireNonNull(status, "status must not be null");
    }

    /** 새 Job 생성. 항상 QUEUED 상태로 시작한다. */
    public static BacktestJob create(String strategy, BacktestPeriod period) {
        if (strategy.isBlank()) {
            throw new IllegalArgumentException("strategy must not be blank");
        }
        return new BacktestJob(null, strategy, period, BacktestStatus.QUEUED);
    }

    /** 저장소에서 읽어온 상태를 복원한다. 불변식 검증 없이 값을 그대로 담는다. */
    public static BacktestJob restore(
            Long id, String strategy, BacktestPeriod period, BacktestStatus status) {
        return new BacktestJob(id, strategy, period, status);
    }

    public Long getId() {
        return id;
    }

    public String getStrategy() {
        return strategy;
    }

    public BacktestPeriod getPeriod() {
        return period;
    }

    public BacktestStatus getStatus() {
        return status;
    }
}
