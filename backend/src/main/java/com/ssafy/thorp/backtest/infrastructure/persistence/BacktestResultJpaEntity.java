package com.ssafy.thorp.backtest.infrastructure.persistence;

import com.ssafy.thorp.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDate;
import java.util.List;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * {@code backtest_result} 테이블 매핑. Job과 1:1({@code backtest_job_id} unique). 도메인 모델과는 {@link
 * BacktestResultMapper}로 변환한다.
 *
 * <p>{@code backtest_job_id}는 JPA 연관 없이 단순 컬럼으로 둔다. Worker(Python)가 이 테이블에 직접 INSERT하므로 컬럼 계약만 있으면
 * 되고, FK 제약은 마이그레이션 도구 도입 시 추가한다. 자산 시계열은 JSON 컬럼 하나에 배열로 저장한다(PostgreSQL jsonb, H2 json).
 */
@Entity
@Table(
        name = "backtest_result",
        uniqueConstraints =
                @UniqueConstraint(name = "uk_backtest_result_job", columnNames = "backtest_job_id"))
public class BacktestResultJpaEntity extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "backtest_job_id", nullable = false)
    private Long backtestJobId;

    @Column(nullable = false)
    private long initialEquity;

    @Column(nullable = false)
    private long finalEquity;

    private Double totalReturn;

    private Double cagr;

    private Double maxDrawdown;

    private LocalDate maxDrawdownPeakDate;

    private LocalDate maxDrawdownTroughDate;

    private LocalDate maxDrawdownRecoveryDate;

    private Double volatility;

    private Double sharpeRatio;

    @Column(nullable = false)
    private int tradeCount;

    private Double winRate;

    private Double profitFactor;

    @Column(nullable = false, length = 20)
    private String metricDefinitionVersion;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "equity_curve", nullable = false)
    private List<EquityPointJson> equityCurve;

    protected BacktestResultJpaEntity() {}

    BacktestResultJpaEntity(
            Long id,
            Long backtestJobId,
            long initialEquity,
            long finalEquity,
            Double totalReturn,
            Double cagr,
            Double maxDrawdown,
            LocalDate maxDrawdownPeakDate,
            LocalDate maxDrawdownTroughDate,
            LocalDate maxDrawdownRecoveryDate,
            Double volatility,
            Double sharpeRatio,
            int tradeCount,
            Double winRate,
            Double profitFactor,
            String metricDefinitionVersion,
            List<EquityPointJson> equityCurve) {
        this.id = id;
        this.backtestJobId = backtestJobId;
        this.initialEquity = initialEquity;
        this.finalEquity = finalEquity;
        this.totalReturn = totalReturn;
        this.cagr = cagr;
        this.maxDrawdown = maxDrawdown;
        this.maxDrawdownPeakDate = maxDrawdownPeakDate;
        this.maxDrawdownTroughDate = maxDrawdownTroughDate;
        this.maxDrawdownRecoveryDate = maxDrawdownRecoveryDate;
        this.volatility = volatility;
        this.sharpeRatio = sharpeRatio;
        this.tradeCount = tradeCount;
        this.winRate = winRate;
        this.profitFactor = profitFactor;
        this.metricDefinitionVersion = metricDefinitionVersion;
        this.equityCurve = equityCurve;
    }

    public Long getId() {
        return id;
    }

    public Long getBacktestJobId() {
        return backtestJobId;
    }

    public long getInitialEquity() {
        return initialEquity;
    }

    public long getFinalEquity() {
        return finalEquity;
    }

    public Double getTotalReturn() {
        return totalReturn;
    }

    public Double getCagr() {
        return cagr;
    }

    public Double getMaxDrawdown() {
        return maxDrawdown;
    }

    public LocalDate getMaxDrawdownPeakDate() {
        return maxDrawdownPeakDate;
    }

    public LocalDate getMaxDrawdownTroughDate() {
        return maxDrawdownTroughDate;
    }

    public LocalDate getMaxDrawdownRecoveryDate() {
        return maxDrawdownRecoveryDate;
    }

    public Double getVolatility() {
        return volatility;
    }

    public Double getSharpeRatio() {
        return sharpeRatio;
    }

    public int getTradeCount() {
        return tradeCount;
    }

    public Double getWinRate() {
        return winRate;
    }

    public Double getProfitFactor() {
        return profitFactor;
    }

    public String getMetricDefinitionVersion() {
        return metricDefinitionVersion;
    }

    public List<EquityPointJson> getEquityCurve() {
        return equityCurve;
    }
}
