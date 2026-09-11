package com.ssafy.thorp.backtest.infrastructure.persistence;

import com.ssafy.thorp.backtest.domain.model.BacktestRun;
import com.ssafy.thorp.backtest.domain.model.PositionSizingMode;
import com.ssafy.thorp.backtest.domain.model.ProgressStage;
import com.ssafy.thorp.backtest.domain.model.RunStatus;
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
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * {@code backtest_run} 테이블 매핑. 도메인 모델과는 {@link BacktestRunMapper}로 변환한다.
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
@Table(name = "backtest_run")
public class BacktestRunJpaEntity extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "backtest_run_seq_generator")
    @SequenceGenerator(
            name = "backtest_run_seq_generator",
            sequenceName = "backtest_run_seq",
            allocationSize = 1)
    private Long id;

    @Column(nullable = false, unique = true, length = 32)
    private String backtestId;

    @Column(nullable = false, length = BacktestRun.MAX_STRATEGY_ID_LENGTH)
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
    private RunStatus status;

    /**
     * 아래는 워커 이벤트가 갱신하는 실행 기록이다(도메인 {@code RunState}).
     *
     * <p>마이그레이션이 만든 나머지 컬럼(dedup_key·execution_type·rules_version·cost_profile_version·
     * data_version·request_spec·versions·effective_start/end·queued_at·started_at·finished_at·
     * published_at·publish_count·dlq_count·cancel_requested_at)은 아직 매핑하지 않는다. 그 값을 쓰는 접수·재사용· 취소·측정
     * 경로가 T005·T010·T011·T012라서, 쓰는 코드와 같은 MR에서 매핑을 붙이는 편이 낫다. 컬럼이 실제로 있는지는 {@code
     * FlywayMigrationTest}가 본다. NOT NULL 컬럼에는 모두 기본값이 있어 INSERT는 문제없다.
     */
    @Column(nullable = false)
    private int attempt;

    @Column(length = 255)
    private String currentExecutionId;

    @Column(nullable = false)
    private boolean partial;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private ProgressStage progressStage;

    private LocalDate progressLastTradingDate;

    private Integer progressCompletedDays;

    private Integer progressTotalDays;

    @Column(length = 64)
    private String failureCode;

    @Column(columnDefinition = "text")
    private String failureMessage;

    // --- 접수가 확정한 계획(spec 004 T005). 값이 없는 행은 T003까지 만들어진 실행이다. ---

    @Column(length = 64)
    private String dedupKey;

    @Column(length = 32)
    private String rulesVersion;

    @Column(length = 32)
    private String costProfileVersion;

    @Column(length = 64)
    private String dataVersion;

    @JdbcTypeCode(SqlTypes.JSON)
    private Map<String, Object> requestSpec;

    @JdbcTypeCode(SqlTypes.JSON)
    private Map<String, Object> versions;

    private LocalDate effectiveStart;

    private LocalDate effectiveEnd;

    // --- 전달 보장과 시각(R-18). publishCount 는 confirm 이 아니라 발행 시도 횟수다. ---

    private Instant publishedAt;

    @Column(nullable = false)
    private int publishCount;

    @Column(nullable = false)
    private int dlqCount;

    @Column(nullable = false)
    private Instant queuedAt;

    private Instant startedAt;

    private Instant finishedAt;

    /** backtestId에 쓸 날짜 접두. 저장 전 한 번 예약하고 PK와의 결합은 PrePersist가 한다. */
    @Transient private LocalDate backtestIdDate;

    protected BacktestRunJpaEntity() {}

    BacktestRunJpaEntity(
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
            RunStatus status,
            int attempt,
            String currentExecutionId,
            boolean partial,
            ProgressStage progressStage,
            LocalDate progressLastTradingDate,
            Integer progressCompletedDays,
            Integer progressTotalDays,
            String failureCode,
            String failureMessage) {
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
        this.attempt = attempt;
        this.currentExecutionId = currentExecutionId;
        this.partial = partial;
        this.progressStage = progressStage;
        this.progressLastTradingDate = progressLastTradingDate;
        this.progressCompletedDays = progressCompletedDays;
        this.progressTotalDays = progressTotalDays;
        this.failureCode = failureCode;
        this.failureMessage = failureMessage;
    }

    /** 저장 직전 한 번만 외부 식별자를 예약한다. 이미 값이 있으면 거부한다. */
    void assignBacktestIdOn(LocalDate date) {
        if (this.backtestId != null) {
            throw new IllegalStateException("backtestId already assigned: " + this.backtestId);
        }
        this.backtestIdDate = date;
    }

    /**
     * 접수가 확정한 계획을 반영한다. 생성자 인자를 늘리는 대신 별도 메서드로 둔다 — 이 값들은 저장 직전 한 번만 채워지고, 접수 경로를 거치지 않는 실행(T003까지의
     * 행, 테스트 픽스처)은 채우지 않는다.
     */
    void applyPlan(
            String dedupKey,
            String rulesVersion,
            String costProfileVersion,
            String dataVersion,
            LocalDate effectiveStart,
            LocalDate effectiveEnd,
            Map<String, Object> requestSpec,
            Map<String, Object> versions) {
        this.dedupKey = dedupKey;
        this.rulesVersion = rulesVersion;
        this.costProfileVersion = costProfileVersion;
        this.dataVersion = dataVersion;
        this.effectiveStart = effectiveStart;
        this.effectiveEnd = effectiveEnd;
        this.requestSpec = requestSpec;
        this.versions = versions;
    }

    /** 발행 시도·confirm·DLQ·시각. 상태 전이와 따로 갱신된다(R-18). */
    void applyLifecycle(
            Instant queuedAt,
            Instant startedAt,
            Instant finishedAt,
            Instant publishedAt,
            int publishCount,
            int dlqCount) {
        if (queuedAt != null) {
            this.queuedAt = queuedAt;
        }
        this.startedAt = startedAt;
        this.finishedAt = finishedAt;
        this.publishedAt = publishedAt;
        this.publishCount = publishCount;
        this.dlqCount = dlqCount;
    }

    /**
     * 상태 전이 결과를 반영한다. 전이 규칙은 도메인이 이미 검증했으므로 여기서는 값만 바꾼다.
     *
     * <p><b>이 메서드가 쓰는 컬럼과 발행 기록이 쓰는 컬럼은 겹치지 않는다.</b> 이벤트 처리(상태·진행·시작/종료 시각)와 발행
     * 경로(published_at·publish_count)는 서로 다른 스레드에서 동시에 돈다 — 한쪽이 상대의 컬럼까지 통째로 덮어쓰면 늦게 저장한 쪽이 상대의 갱신을
     * 되돌린다. 그래서 발행 기록은 여기가 아니라 전용 UPDATE 로 처리한다.
     */
    void applyState(
            RunStatus status,
            int attempt,
            String currentExecutionId,
            boolean partial,
            ProgressStage progressStage,
            LocalDate progressLastTradingDate,
            Integer progressCompletedDays,
            Integer progressTotalDays,
            String failureCode,
            String failureMessage,
            Instant startedAt,
            Instant finishedAt) {
        this.status = status;
        this.attempt = attempt;
        this.currentExecutionId = currentExecutionId;
        this.partial = partial;
        this.progressStage = progressStage;
        this.progressLastTradingDate = progressLastTradingDate;
        this.progressCompletedDays = progressCompletedDays;
        this.progressTotalDays = progressTotalDays;
        this.failureCode = failureCode;
        this.failureMessage = failureMessage;
        this.startedAt = startedAt;
        this.finishedAt = finishedAt;
    }

    @PrePersist
    void fillBacktestId() {
        if (backtestId == null) {
            backtestId = BacktestId.of(backtestIdDate, id).value();
        }
    }

    public String getDedupKey() {
        return dedupKey;
    }

    public String getRulesVersion() {
        return rulesVersion;
    }

    public String getCostProfileVersion() {
        return costProfileVersion;
    }

    public String getDataVersion() {
        return dataVersion;
    }

    public Map<String, Object> getRequestSpec() {
        return requestSpec;
    }

    public Map<String, Object> getVersions() {
        return versions;
    }

    public LocalDate getEffectiveStart() {
        return effectiveStart;
    }

    public LocalDate getEffectiveEnd() {
        return effectiveEnd;
    }

    public Instant getPublishedAt() {
        return publishedAt;
    }

    public int getPublishCount() {
        return publishCount;
    }

    public int getDlqCount() {
        return dlqCount;
    }

    public Instant getQueuedAt() {
        return queuedAt;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getFinishedAt() {
        return finishedAt;
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

    public RunStatus getStatus() {
        return status;
    }

    public int getAttempt() {
        return attempt;
    }

    public String getCurrentExecutionId() {
        return currentExecutionId;
    }

    public boolean isPartial() {
        return partial;
    }

    public ProgressStage getProgressStage() {
        return progressStage;
    }

    public LocalDate getProgressLastTradingDate() {
        return progressLastTradingDate;
    }

    public Integer getProgressCompletedDays() {
        return progressCompletedDays;
    }

    public Integer getProgressTotalDays() {
        return progressTotalDays;
    }

    public String getFailureCode() {
        return failureCode;
    }

    public String getFailureMessage() {
        return failureMessage;
    }
}
