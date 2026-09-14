package com.ssafy.thorp.backtest.infrastructure.persistence;

import com.ssafy.thorp.backtest.application.command.SaveBacktestResultCommand;
import com.ssafy.thorp.backtest.application.port.message.BenchmarkResult;
import com.ssafy.thorp.backtest.application.port.message.EngineOutput;
import com.ssafy.thorp.backtest.application.port.message.WeightsSource;
import com.ssafy.thorp.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.MapsId;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.List;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * {@code backtest_result} 테이블 매핑. Job과 1:1이며 PK가 곧 FK다({@code @MapsId}).
 *
 * <p>저장 규칙: 질의(필터·정렬·조인)에 쓰는 값만 컬럼, 표시용 값은 JSON. decimal은 전부 JSON에 둔다 — {@code NUMERIC} 컬럼은 scale을
 * 임의로 정해야 하고 그 자릿수를 넘는 값을 조용히 반올림하지만, JSON은 계약이 준 값과 scale을 그대로 보존한다. JSON 직렬화는 메시지 ObjectMapper가
 * 맡아 decimal을 문자열로 쓰므로 jsonb의 숫자 정규화도 일어나지 않는다.
 *
 * <p>실패한 실행({@code run.status=FAILED})은 {@code errorCode}·{@code errorMessage}만 채워지고 {@code
 * performance}·{@code execution}·{@code benchmark}는 null, {@code portfolioSeries}는 빈 리스트다. 없는 성과를
 * 0으로 채우지 않는다.
 */
@Entity
@Table(name = "backtest_result")
public class BacktestResultJpaEntity extends BaseEntity {

    @Id private Long runId;

    @OneToOne(fetch = FetchType.LAZY)
    @MapsId
    @JoinColumn(name = "run_id")
    private BacktestRunJpaEntity job;

    @Column(nullable = false, length = 255)
    private String executionId;

    @Column(nullable = false, length = 255)
    private String finishedEventId;

    @Column(nullable = false)
    private Instant startedAt;

    @Column(nullable = false)
    private Instant finishedAt;

    @Column(length = 255)
    private String errorCode;

    @Column(columnDefinition = "TEXT")
    private String errorMessage;

    @JdbcTypeCode(SqlTypes.JSON)
    private EngineOutput.PerformanceResult performance;

    @JdbcTypeCode(SqlTypes.JSON)
    private EngineOutput.ExecutionResult execution;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private List<EngineOutput.PortfolioSnapshot> portfolioSeries;

    @JdbcTypeCode(SqlTypes.JSON)
    private BenchmarkResult benchmark;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private EngineOutput.Diagnostics diagnostics;

    // v2 블록(spec 004 FR-026·FR-027·FR-030, T009). 표시용이라 JSONB 이고 weights_source 만 필터용 스칼라다.
    @JdbcTypeCode(SqlTypes.JSON)
    private List<EngineOutput.WeightsPoint> weightsSeries;

    @Enumerated(EnumType.STRING)
    @Column(length = 10)
    private WeightsSource weightsSource;

    @JdbcTypeCode(SqlTypes.JSON)
    private EngineOutput.Costs costs;

    @JdbcTypeCode(SqlTypes.JSON)
    private EngineOutput.Liquidation liquidation;

    @JdbcTypeCode(SqlTypes.JSON)
    private EngineOutput.Analytics analytics;

    @JdbcTypeCode(SqlTypes.JSON)
    private List<EngineOutput.ExceptionRecord> exceptions;

    @JdbcTypeCode(SqlTypes.JSON)
    private EngineOutput.Versions versions;

    protected BacktestResultJpaEntity() {}

    private BacktestResultJpaEntity(BacktestRunJpaEntity job, SaveBacktestResultCommand command) {
        EngineOutput output = command.engineOutput();
        EngineOutput.RunResult run = output.run();
        this.job = job;
        this.executionId = command.executionId();
        this.finishedEventId = command.eventId();
        this.startedAt = run.startedAt();
        this.finishedAt = run.finishedAt();
        this.errorCode = run.error() == null ? null : run.error().code();
        this.errorMessage = run.error() == null ? null : run.error().message();
        this.performance = output.performance();
        this.execution = output.execution();
        this.portfolioSeries = output.portfolioSeries();
        this.benchmark = command.benchmark();
        this.diagnostics = output.diagnostics();
        this.weightsSeries = output.weightsSeries();
        this.weightsSource = output.weightsSource();
        this.costs = output.costs();
        this.liquidation = output.liquidation();
        this.analytics = output.analytics();
        this.exceptions = output.exceptions();
        this.versions = output.versions();
    }

    /** 계약 DTO → 엔티티. 결과 도메인 모델이 없으므로 별도 Mapper 없이 여기서 매핑한다. */
    static BacktestResultJpaEntity from(
            BacktestRunJpaEntity job, SaveBacktestResultCommand command) {
        return new BacktestResultJpaEntity(job, command);
    }

    public Long getRunId() {
        return runId;
    }

    public String getExecutionId() {
        return executionId;
    }

    public String getFinishedEventId() {
        return finishedEventId;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getFinishedAt() {
        return finishedAt;
    }

    /** null = 성공. */
    public String getErrorCode() {
        return errorCode;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    /** null = 실패로 계산 불가. */
    public EngineOutput.PerformanceResult getPerformance() {
        return performance;
    }

    /** null = 실패로 계산 불가. */
    public EngineOutput.ExecutionResult getExecution() {
        return execution;
    }

    public List<EngineOutput.PortfolioSnapshot> getPortfolioSeries() {
        return portfolioSeries;
    }

    /** null = 벤치마크 미지정 또는 실패. */
    public BenchmarkResult getBenchmark() {
        return benchmark;
    }

    public EngineOutput.Diagnostics getDiagnostics() {
        return diagnostics;
    }

    /** null = 엔진이 채우지 않았다(실패 또는 v2 이전 결과). 아래 여섯 getter 도 같다. */
    public List<EngineOutput.WeightsPoint> getWeightsSeries() {
        return weightsSeries;
    }

    public WeightsSource getWeightsSource() {
        return weightsSource;
    }

    public EngineOutput.Costs getCosts() {
        return costs;
    }

    public EngineOutput.Liquidation getLiquidation() {
        return liquidation;
    }

    public EngineOutput.Analytics getAnalytics() {
        return analytics;
    }

    public List<EngineOutput.ExceptionRecord> getExceptions() {
        return exceptions;
    }

    public EngineOutput.Versions getVersions() {
        return versions;
    }
}
