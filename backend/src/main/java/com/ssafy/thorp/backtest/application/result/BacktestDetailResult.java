package com.ssafy.thorp.backtest.application.result;

import com.ssafy.thorp.backtest.application.port.message.EngineOutput;
import com.ssafy.thorp.backtest.domain.model.BacktestRequest;
import com.ssafy.thorp.backtest.domain.model.BacktestRun;
import com.ssafy.thorp.backtest.domain.model.ProgressStage;
import com.ssafy.thorp.backtest.domain.model.ResultSource;
import com.ssafy.thorp.backtest.domain.model.RunProgress;
import com.ssafy.thorp.backtest.domain.model.RunStatus;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@code GET /backtests/{backtestId}}의 유스케이스 출력(spec 004 contracts/rest-api.md).
 *
 * <p>식별자·상태·완료 범위는 <b>요청</b> 기준이고 진행·결과는 <b>실행</b> 기준이다. 둘이 갈리는 지점이 취소다(R-04): 먼저 취소한 요청은 실행이 계속
 * 돌아도 자기 스냅샷 날짜를 넘는 범위를 보지 않는다.
 *
 * @param status 실행 상태에 요청의 취소를 얹은 값. 요청이 취소됐으면 실행이 계속 돌아도 CANCELLED다
 * @param queueWaitSeconds 접수부터 실행 시작까지. 아직 시작 전이면 지금까지 기다린 시간이다
 * @param summaryUnavailable 요약을 줄 수 없는 이유. null = 정상(요약이 있거나 아직 끝나지 않았다)
 */
public record BacktestDetailResult(
        String backtestId,
        RunStatus status,
        ResultSource resultSource,
        boolean partial,
        Progress progress,
        Instant queuedAt,
        Instant startedAt,
        Instant finishedAt,
        Long queueWaitSeconds,
        Map<String, Object> config,
        Map<String, Object> versions,
        Summary summary,
        String summaryUnavailable,
        Failure failure) {

    /**
     * 진행 정도(Clarify Q4). QUEUED에서는 null이다 — 아직 아무 거래일도 처리하지 않았는데 0%를 보여 주면 "시작했다"로 읽힌다.
     *
     * @param lastTradingDate 처리한 마지막 거래일. 취소한 요청은 자기 스냅샷 날짜다
     */
    public record Progress(
            ProgressStage stage,
            LocalDate lastTradingDate,
            Integer completedTradingDays,
            Integer totalTradingDays) {}

    /**
     * 전체 기간 성과(rest-api.md {@code summary}). 핵심 숫자 3개는 {@code netTotalReturn}·{@code
     * maxDrawdown}·{@code totalTradingCost}다. 앞 열두 항목은 v1부터 있던 값이고 뒤 여섯이 v2 추가분이다.
     *
     * @param netTotalReturn 비용 차감 후 전체 수익률(FR-027). null = 미산출
     * @param totalTradingCost 거래비용 합계. null = 미산출
     * @param costs 비용 네 갈래와 합. null = 미저장
     * @param liquidation 청산 가정치(FR-017). null = 요청이 청산을 끔
     * @param analytics 파생 지표 중 요약에 넣는 둘. 나머지는 결과 JSONB 에 있다. null = 미저장
     * @param exceptionCount 예외 처리 기록 건수. 본문은 {@code /exceptions}
     */
    public record Summary(
            BigDecimal initialEquity,
            BigDecimal finalEquity,
            String currency,
            BigDecimal totalReturn,
            BigDecimal cagr,
            BigDecimal maxDrawdown,
            BigDecimal sharpeRatio,
            BigDecimal volatility,
            int tradeCount,
            BigDecimal winRate,
            BigDecimal totalCommission,
            BigDecimal totalSlippageCost,
            BigDecimal netTotalReturn,
            BigDecimal totalTradingCost,
            EngineOutput.Costs costs,
            EngineOutput.Liquidation liquidation,
            AnalyticsSummary analytics,
            int exceptionCount) {}

    /** {@code analytics} 중 요약에 실리는 둘(rest-api.md). */
    public record AnalyticsSummary(Integer recoveryDays, BigDecimal turnover) {}

    /** 실패 사유. 코드 집합은 엔진 소유이고 HTTP {@code ErrorCode}와 다르다. */
    public record Failure(String code, String message) {}

    /** 결과 행이 아직 없는 실행(QUEUED·RUNNING). */
    public static BacktestDetailResult withoutResult(
            BacktestRequest request, BacktestRun run, String currency, Instant now) {
        return build(request, run, currency, now, null, null);
    }

    /** 결과 행이 있는 실행(COMPLETED·FAILED·CANCELLED). 상태에 따라 요약과 실패가 갈린다. */
    public static BacktestDetailResult of(
            BacktestRequest request,
            BacktestRun run,
            String currency,
            Instant now,
            BacktestResultSummary result) {
        Summary summary = run.getStatus() == RunStatus.COMPLETED ? summary(result) : null;
        Failure failure =
                result.errorCode() == null
                        ? null
                        : new Failure(result.errorCode(), result.errorMessage());
        return build(request, run, currency, now, summary, failure);
    }

    private static BacktestDetailResult build(
            BacktestRequest request,
            BacktestRun run,
            String currency,
            Instant now,
            Summary summary,
            Failure failure) {
        boolean cancelledRequest = request.isCancelled();
        RunStatus status = cancelledRequest ? RunStatus.CANCELLED : run.getStatus();
        LocalDate completedRange =
                request.completedRangeOf(
                        run.getProgress() == null ? null : run.getProgress().lastTradingDate());
        // 먼저 취소해 실행이 계속 돈 요청은 자기 스냅샷 구간의 요약을 갖지 못한다 — 백엔드가 지표를
        // 다시 계산하지 않기 때문이다(원칙 1). 요약이 없는 이유를 숨기지 않고 이름을 붙여 돌려준다.
        String summaryUnavailable = null;
        Summary visibleSummary = summary;
        if (cancelledRequest && !request.isStoppedRun()) {
            visibleSummary = null;
            summaryUnavailable = "CANCELLED_WHILE_RUN_CONTINUED";
        }
        return new BacktestDetailResult(
                request.getBacktestId().value(),
                status,
                request.getResultSource(),
                cancelledRequest || run.isPartial(),
                progress(run, completedRange),
                run.getLifecycle().queuedAt(),
                run.getLifecycle().startedAt(),
                run.getLifecycle().finishedAt(),
                queueWaitSeconds(run, now),
                config(run, currency),
                run.getPlan().versions(),
                visibleSummary,
                summaryUnavailable,
                failure);
    }

    /** 완료 범위는 요청 기준으로 덮어쓴다. 나머지 진행 수치는 실행의 것이다. */
    private static Progress progress(BacktestRun run, LocalDate completedRange) {
        RunProgress runProgress = run.getProgress();
        if (runProgress == null) {
            return completedRange == null
                    ? null
                    : new Progress(ProgressStage.COMPUTING, completedRange, null, null);
        }
        return new Progress(
                runProgress.stage(),
                completedRange,
                runProgress.completedTradingDays(),
                runProgress.totalTradingDays());
    }

    private static Long queueWaitSeconds(BacktestRun run, Instant now) {
        Instant queuedAt = run.getLifecycle().queuedAt();
        if (queuedAt == null) {
            return null;
        }
        Instant until =
                run.getLifecycle().startedAt() == null ? now : run.getLifecycle().startedAt();
        return Math.max(0, Duration.between(queuedAt, until).toSeconds());
    }

    /**
     * 무엇을 어떻게 돌렸는가. 접수가 저장한 요청 사본에 실효 기간을 얹는다.
     *
     * <p>요청 사본이 없는 실행(접수 경로를 거치지 않은 행)은 실행 스칼라 컬럼으로 최소한만 채운다.
     */
    private static Map<String, Object> config(BacktestRun run, String currency) {
        Map<String, Object> config = new LinkedHashMap<>(run.getPlan().requestSpecOrEmpty());
        if (config.isEmpty()) {
            config.put("strategyId", run.getStrategyId());
            config.put("rulesVersion", run.getStrategyVersion());
            config.put("parameters", run.getParameters());
            config.put(
                    "instrumentIds",
                    run.getInstrumentIds().stream().map(id -> id.value()).toList());
            config.put("start", run.getPeriod().startDate().toString());
            config.put("end", run.getPeriod().endDate().toString());
            // 금액은 문자열이다. 요청 사본 경로(JSONB)와 표기를 맞춘다.
            config.put("initialCash", run.getInitialCash().toPlainString());
        }
        config.put("currency", currency);
        if (run.getPlan().effectiveStart() != null) {
            config.put("effectiveStart", run.getPlan().effectiveStart().toString());
        }
        if (run.getPlan().effectiveEnd() != null) {
            config.put("effectiveEnd", run.getPlan().effectiveEnd().toString());
        }
        return config;
    }

    private static Summary summary(BacktestResultSummary result) {
        EngineOutput.PerformanceResult p = result.performance();
        EngineOutput.ExecutionResult e = result.execution();
        return new Summary(
                p.initialEquity(),
                p.finalEquity(),
                p.currency(),
                p.totalReturn(),
                p.cagr(),
                p.maxDrawdown(),
                p.sharpeRatio(),
                p.volatility(),
                p.tradeCount(),
                p.winRate(),
                e.totalCommission(),
                e.totalSlippageCost(),
                p.netTotalReturn(),
                p.totalTradingCost(),
                result.costs(),
                result.liquidation(),
                result.analytics() == null
                        ? null
                        : new AnalyticsSummary(
                                result.analytics().recoveryDays(), result.analytics().turnover()),
                result.exceptionCount());
    }
}
