package com.ssafy.thorp.backtest.infrastructure.persistence;

import com.ssafy.thorp.backtest.domain.model.BacktestJob;
import com.ssafy.thorp.backtest.domain.model.BacktestStatus;
import com.ssafy.thorp.backtest.domain.model.PositionSizingMode;
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
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * {@code backtest_job} 테이블 매핑. 도메인 모델과는 {@link BacktestJobMapper}로 변환한다.
 *
 * <p>PK가 SEQUENCE라 {@code persist()} 시점에 값이 나오고, 그 PK로 {@code backtest_id}를 INSERT 전에 채우므로 컬럼을 NOT
 * NULL로 둘 수 있고 사후 UPDATE가 없다.
 *
 * <p>결합은 {@code @PrePersist}에서 한다. Hibernate는 {@code persist()} 호출 시점의 필드 상태로 INSERT 문을 만들기 때문에
 * persist 이후에 필드를 바꾸면 그 값이 INSERT에 실리지 않는다. {@code @PrePersist}는 PK 채번 뒤·상태 스냅샷 전에 불린다.
 *
 * <p>{@code parameters}와 {@code instrument_ids}는 PostgreSQL jsonb 컬럼이다. 종목 조인 테이블은 MVP에 과하고,
 * Hibernate 6 네이티브 매핑이라 라이브러리를 더하지 않는다.
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

    @Column(nullable = false, length = BacktestJob.MAX_STRATEGY_ID_LENGTH)
    private String strategyId;

    @Column(nullable = false, length = 32)
    private String strategyVersion;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private Map<String, Object> parameters;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private List<String> instrumentIds;

    @Column(nullable = false)
    private LocalDate startDate;

    @Column(nullable = false)
    private LocalDate endDate;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal initialCash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private PositionSizingMode positionSizingType;

    @Column(precision = 5, scale = 4)
    private BigDecimal maxPositionPct;

    @Column(length = 32)
    private String benchmarkInstrumentId;

    @Column(nullable = false)
    private int randomSeed;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private BacktestStatus status;

    /** backtestId에 쓸 날짜 접두. 저장 전 한 번 예약하고 PK와의 결합은 PrePersist가 한다. */
    @Transient private LocalDate backtestIdDate;

    protected BacktestJobJpaEntity() {}

    BacktestJobJpaEntity(
            Long id,
            String backtestId,
            String strategyId,
            String strategyVersion,
            Map<String, Object> parameters,
            List<String> instrumentIds,
            LocalDate startDate,
            LocalDate endDate,
            BigDecimal initialCash,
            PositionSizingMode positionSizingType,
            BigDecimal maxPositionPct,
            String benchmarkInstrumentId,
            int randomSeed,
            BacktestStatus status) {
        this.id = id;
        this.backtestId = backtestId;
        this.strategyId = strategyId;
        this.strategyVersion = strategyVersion;
        this.parameters = parameters;
        this.instrumentIds = instrumentIds;
        this.startDate = startDate;
        this.endDate = endDate;
        this.initialCash = initialCash;
        this.positionSizingType = positionSizingType;
        this.maxPositionPct = maxPositionPct;
        this.benchmarkInstrumentId = benchmarkInstrumentId;
        this.randomSeed = randomSeed;
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

    public String getStrategyId() {
        return strategyId;
    }

    public String getStrategyVersion() {
        return strategyVersion;
    }

    public Map<String, Object> getParameters() {
        return parameters;
    }

    public List<String> getInstrumentIds() {
        return instrumentIds;
    }

    public LocalDate getStartDate() {
        return startDate;
    }

    public LocalDate getEndDate() {
        return endDate;
    }

    public BigDecimal getInitialCash() {
        return initialCash;
    }

    public PositionSizingMode getPositionSizingType() {
        return positionSizingType;
    }

    public BigDecimal getMaxPositionPct() {
        return maxPositionPct;
    }

    public String getBenchmarkInstrumentId() {
        return benchmarkInstrumentId;
    }

    public int getRandomSeed() {
        return randomSeed;
    }

    public BacktestStatus getStatus() {
        return status;
    }
}
