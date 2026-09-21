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
 * @param cancelRequested 취소했지만 실행이 아직 끝나지 않았다(워커의 FINISHED 대기). 다음 진행 배치 안에 반영된다
 * @param summaryUnavailable 요약을 줄 수 없는 이유. null = 정상(요약이 있거나 아직 끝나지 않았다). {@code
 *     CANCELLED_WHILE_RUN_CONTINUED} 먼저 취소해 실행이 계속 돈 요청 / {@code CANCELLED_BEFORE_START} 대기 중 취소돼
 *     계산이 없었다 / {@code CANCEL_UNANSWERED} 취소 무응답 강제 종결(T014)
 */
public record BacktestDetailResult(
        String backtestId,
        RunStatus status,
        ResultSource resultSource,
        boolean partial,
        boolean cancelRequested,
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
        return build(request, run, currency, now, null, null, null);
    }

    /** 대기 중 취소돼 계산이 없었던 실행. 결과 행이 없는 것이 정상이다. */
    public static BacktestDetailResult cancelledBeforeStart(
            BacktestRequest request, BacktestRun run, String currency, Instant now) {
        return build(request, run, currency, now, null, "CANCELLED_BEFORE_START", null);
    }

    /**
     * 워커의 산출물 없이 백엔드가 끊은 실패(T010 DLQ 종결, T014 취소 무응답). 결과 행이 없는 것이 정상이므로 실패 사유는 <b>실행 행</b>에서 읽는다 —
     * 이것을 결과 유실 500 으로 다루면 사용자는 무슨 일이 있었는지 영영 볼 수 없다.
     */
    public static BacktestDetailResult failedWithoutResult(
            BacktestRequest request, BacktestRun run, String currency, Instant now) {
        return build(
                request,
                run,
                currency,
                now,
                null,
                null,
                new Failure(run.getFailureCode(), run.getFailureMessage()));
    }

    /**
     * 결과 행이 있는 실행(COMPLETED·FAILED·CANCELLED). 상태에 따라 요약과 실패가 갈린다.
     *
     * <p>취소 실행의 요약은 부분 구간 기준이다(partial). 성과가 없는 결과(실패)는 요약도 없다.
     */
    public static BacktestDetailResult of(
            BacktestRequest request,
            BacktestRun run,
            String currency,
            Instant now,
            BacktestResultSummary result) {
        Summary summary = result.performance() == null ? null : summary(result);
        // 결과 행의 사유가 먼저다. 없으면 실행 행을 본다 — 실행 시간 상한으로 멈춘 협력 취소는
        // 결과(부분 산출물)는 있지만 오류가 아니라 사유가 실행 행에만 있다(R-19).
        Failure failure =
                result.errorCode() != null
                        ? new Failure(result.errorCode(), result.errorMessage())
                        : run.getFailureCode() == null
                                ? null
                                : new Failure(run.getFailureCode(), run.getFailureMessage());
        return build(
                request,
                run,
                currency,
                now,
                summary,
                null,
                failure,
                withReported(
                        run.getPlan().versions(), result.workerVersion(), result.dependencies()));
    }

    /**
     * 접수가 확정한 참조 버전에 결과를 낸 워커의 자기 보고({@code versions.worker})와 그 환경의 수치 라이브러리 버전({@code
     * versions.dependencies}, T028)을 얹는다. 접수 시점에는 어느 워커가 계산할지 모르므로 이 두 값만 결과 행에서 온다. 보고가 없으면 키를 만들지
     * 않는다 — 모르는 값을 채우지 않는다.
     */
    private static Map<String, Object> withReported(
            Map<String, Object> versions, String worker, EngineOutput.Dependencies dependencies) {
        if (versions == null || (worker == null && dependencies == null)) {
            return versions;
        }
        Map<String, Object> merged = new LinkedHashMap<>(versions);
        if (worker != null) {
            merged.put("worker", worker);
        }
        if (dependencies != null) {
            Map<String, Object> libraries = new LinkedHashMap<>();
            libraries.put("numpy", dependencies.numpy());
            libraries.put("scipy", dependencies.scipy());
            merged.put("dependencies", libraries);
        }
        return merged;
    }

    private static BacktestDetailResult build(
            BacktestRequest request,
            BacktestRun run,
            String currency,
            Instant now,
            Summary summary,
            String unavailableReason,
            Failure failure) {
        return build(
                request,
                run,
                currency,
                now,
                summary,
                unavailableReason,
                failure,
                run.getPlan().versions());
    }

    private static BacktestDetailResult build(
            BacktestRequest request,
            BacktestRun run,
            String currency,
            Instant now,
            Summary summary,
            String unavailableReason,
            Failure failure,
            Map<String, Object> versions) {
        boolean cancelledRequest = request.isCancelled();
        RunStatus status = cancelledRequest ? RunStatus.CANCELLED : run.getStatus();
        // 먼저 취소해 실행이 계속 도는 요청은 진행 수치가 실행의 최신 값이 아니라 자기 스냅샷 기준이다.
        boolean pinnedToSnapshot = cancelledRequest && !request.isStoppedRun();
        LocalDate completedRange =
                request.completedRangeOf(
                        run.getProgress() == null ? null : run.getProgress().lastTradingDate());
        // 먼저 취소해 실행이 계속 돈 요청은 자기 스냅샷 구간의 요약을 갖지 못한다 — 백엔드가 지표를
        // 다시 계산하지 않기 때문이다(원칙 1). 요약이 없는 이유를 숨기지 않고 이름을 붙여 돌려준다.
        String summaryUnavailable = unavailableReason;
        Summary visibleSummary = summary;
        if (pinnedToSnapshot) {
            visibleSummary = null;
            summaryUnavailable = "CANCELLED_WHILE_RUN_CONTINUED";
        }
        return new BacktestDetailResult(
                request.getBacktestId().value(),
                status,
                request.getResultSource(),
                cancelledRequest || run.isPartial(),
                cancelledRequest && !run.getStatus().isFinal(),
                progress(run, completedRange, pinnedToSnapshot),
                run.getLifecycle().queuedAt(),
                run.getLifecycle().startedAt(),
                run.getLifecycle().finishedAt(),
                queueWaitSeconds(run, now),
                config(run, currency),
                versions,
                visibleSummary,
                summaryUnavailable,
                failure);
    }

    /**
     * 완료 범위는 요청 기준으로 덮어쓴다. 나머지 진행 수치는 실행의 것이다.
     *
     * <p>스냅샷에 고정된 요청({@code pinnedToSnapshot})은 완료 일수를 주지 않는다 — 실행은 계속 돌아 그 값이 자라지만 날짜는 취소 시점에 멈춰
     * 있어서, 그대로 내보내면 "3월 25일까지인데 100% 완료" 같은 응답이 된다. 취소 시점의 일수를 따로 저장하지 않으므로 채울 값이 없다.
     */
    private static Progress progress(
            BacktestRun run, LocalDate completedRange, boolean pinnedToSnapshot) {
        RunProgress runProgress = run.getProgress();
        if (runProgress == null) {
            return completedRange == null
                    ? null
                    : new Progress(ProgressStage.COMPUTING, completedRange, null, null);
        }
        return new Progress(
                runProgress.stage(),
                completedRange,
                pinnedToSnapshot ? null : runProgress.completedTradingDays(),
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
     *
     * <p><b>006 내 기록의 {@code reopenPayload}도 이것을 부른다</b>(spec 006 research R4). 기록에서 '설정 바꿔 비교'를
     * 누르면 005의 {@code prefillStrategySettings}에 이 모양 그대로 넘어가므로, 006이 같은 조립을 한 벌 더 가지면 두 화면이 다른 조건을
     * 보인다. 그래서 {@code private}으로 두지 않는다 — 부르는 쪽이 둘이라는 사실이 이 접근 범위의 이유다.
     */
    public static Map<String, Object> config(BacktestRun run, String currency) {
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
