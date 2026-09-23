package com.ssafy.thorp.backtest.infrastructure.messaging;

import com.ssafy.thorp.backtest.application.port.message.BacktestEvent;
import com.ssafy.thorp.backtest.application.port.message.EngineOutput;
import com.ssafy.thorp.backtest.application.port.message.FinishedPayload;
import com.ssafy.thorp.backtest.application.port.message.ProgressPayload;
import com.ssafy.thorp.backtest.application.port.message.RunStatus;
import com.ssafy.thorp.backtest.application.port.message.StartedPayload;
import com.ssafy.thorp.backtest.application.port.message.TradeStatus;
import com.ssafy.thorp.backtest.domain.vo.InstrumentId;
import java.time.LocalDate;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 역직렬화 직후의 명시 검증. 계약 기준: {@code docs/specs/backtest-messaging/README.md} §4.2·§4.3·§5·§8.
 *
 * <p>Java record는 참조형 필드가 누락돼도 null로 역직렬화된다. 메시지 ObjectMapper가 잡는 것은 미지의 enum과 필수 정수(primitive)
 * 누락뿐이므로 나머지는 여기서 본다. 중첩 그래프 전체를 훑는다 — 최상위만 보면 {@code performance.initialEquity}가 null인 메시지가 통과해 저장
 * 중 NPE가 나고, 계약 오류가 일시 오류로 오인돼 재시도된다.
 *
 * <p>구조와 상태별 nullable 규칙만 본다. 회계 항등식({@code equity = cash + positionValue} 등)은 재계산하지 않는다 — 그것은 엔진의
 * 책임이다. 계약이 nullable로 정한 필드(성과 지표 개별 null, snapshot의 positions 없음, OPEN 거래의 청산 필드, v2 추가 항목 전부)는
 * 그대로 받는다. 반대로 필수 필드는 기본값으로 보정하지 않는다 — {@code trades}가 누락된 COMPLETED를 {@code []}로 저장하면 "거래가 없었다"는
 * 거짓 결과를 확정한다.
 *
 * <p>위반 시 {@link IllegalArgumentException}에 필드 경로({@code
 * payload.engineOutput.trades[3].entryPrice})를 담아 DLQ에서 원인을 바로 찾을 수 있게 한다.
 */
final class BacktestEventValidator {

    /**
     * 계약 §5: ID 문자열({@code eventId}·{@code executionId}·{@code tradeId}·{@code error.code})의 최대 길이.
     * 저장 컬럼 폭과 같다.
     */
    static final int MAX_ID_LENGTH = 255;

    static final int SCHEMA_VERSION = BacktestRabbitConfig.SCHEMA_VERSION;

    /** 종목 식별자 형식은 도메인 VO와 같은 계약(ADR-0002 D4)이다. 저장 컬럼 `VARCHAR(32)`보다 훨씬 짧아 길이 문제도 여기서 걸러진다. */
    private static final Pattern INSTRUMENT_ID = Pattern.compile(InstrumentId.REGEX);

    private BacktestEventValidator() {}

    static void validateStarted(BacktestEvent<StartedPayload> event) {
        envelope(event);
        if (event.sequence() != 0) {
            fail("sequence", "STARTED must have sequence 0 but was " + event.sequence());
        }
        require(event.payload().startedAt(), "payload.startedAt");
        require(event.payload().stage(), "payload.stage");
    }

    static void validateProgress(BacktestEvent<ProgressPayload> event) {
        envelope(event);
        subsequent(event);
        ProgressPayload payload = event.payload();
        require(payload.stage(), "payload.stage");
        if (payload.completedTradingDays() < 0) {
            fail("payload.completedTradingDays", "must be >= 0");
        }
        if (payload.totalTradingDays() <= 0) {
            fail("payload.totalTradingDays", "must be > 0");
        }
        if (payload.completedTradingDays() > payload.totalTradingDays()) {
            fail(
                    "payload.completedTradingDays",
                    payload.completedTradingDays()
                            + " exceeds totalTradingDays "
                            + payload.totalTradingDays());
        }
        require(payload.lastTradingDate(), "payload.lastTradingDate");
        List<ProgressPayload.ProgressPoint> series =
                require(payload.portfolioSeries(), "payload.portfolioSeries");
        for (int i = 0; i < series.size(); i++) {
            String path = "payload.portfolioSeries[" + i + "]";
            ProgressPayload.ProgressPoint point = require(series.get(i), path);
            require(point.date(), path + ".date");
            require(point.equity(), path + ".equity");
            require(point.cumulativeReturn(), path + ".cumulativeReturn");
            require(point.drawdown(), path + ".drawdown");
        }
    }

    static void validateFinished(BacktestEvent<FinishedPayload> event) {
        envelope(event);
        subsequent(event);
        FinishedPayload payload = event.payload();
        RunStatus status = require(payload.status(), "payload.status");
        String base = "payload.engineOutput";
        EngineOutput output = require(payload.engineOutput(), base);
        run(output.run(), event.runId(), base + ".run");
        if (output.run().status() != status) {
            fail(
                    base + ".run.status",
                    output.run().status() + " differs from payload.status " + status);
        }
        // 계약 §4.3: run.partial 생략은 false다. 값이 있을 때만 비교하면 키를 뺀 채 payload만
        // true인 취소 이벤트가 통과한다. null을 false로 접어 항상 대조한다.
        if (Boolean.TRUE.equals(output.run().partial()) != payload.partial()) {
            fail(base + ".run.partial", "differs from payload.partial " + payload.partial());
        }

        List<EngineOutput.PortfolioSnapshot> series =
                require(output.portfolioSeries(), base + ".portfolioSeries");
        List<EngineOutput.TradeResult> trades = require(output.trades(), base + ".trades");
        EngineOutput.Diagnostics diagnostics = require(output.diagnostics(), base + ".diagnostics");
        List<EngineOutput.Warning> warnings =
                require(diagnostics.warnings(), base + ".diagnostics.warnings");
        snapshots(series, base + ".portfolioSeries");
        trades(trades, base + ".trades");
        // fills 는 nullable 이다 — 없으면 체결을 기록하지 않는 워커가 낸 결과다. 있으면 한 건씩 본다.
        if (output.fills() != null) {
            fills(output.fills(), base + ".fills");
        }
        warnings(warnings, base + ".diagnostics.warnings");

        switch (status) {
            case COMPLETED -> {
                notPartial(payload);
                computed(output, base);
            }
            case CANCELLED -> {
                if (!payload.partial()) {
                    fail("payload.partial", "must be true when status is CANCELLED");
                }
                cancelledRange(payload, output, series, base);
                computed(output, base);
            }
            case FAILED -> {
                notPartial(payload);
                failed(output, base);
            }
        }
    }

    private static void envelope(BacktestEvent<?> event) {
        if (event.schemaVersion() != SCHEMA_VERSION) {
            fail("schemaVersion", "unsupported version " + event.schemaVersion());
        }
        requireId(event.eventId(), "eventId");
        requireNotBlank(event.runId(), "runId");
        requireId(event.executionId(), "executionId");
        require(event.type(), "type");
        if (event.sequence() < 0) {
            fail("sequence", "must be >= 0");
        }
        require(event.occurredAt(), "occurredAt");
        require(event.payload(), "payload");
    }

    /** STARTED만 0이다. 이후 이벤트는 1부터. 연속성은 검증하지 않는다. */
    private static void subsequent(BacktestEvent<?> event) {
        if (event.sequence() < 1) {
            fail("sequence", event.type() + " must have sequence >= 1 but was " + event.sequence());
        }
    }

    private static void run(EngineOutput.RunResult run, String runId, String path) {
        require(run, path);
        require(run.status(), path + ".status");
        require(run.startedAt(), path + ".startedAt");
        require(run.finishedAt(), path + ".finishedAt");
        if (!runId.equals(run.runId())) {
            fail(path + ".runId", run.runId() + " differs from envelope " + runId);
        }
        if (run.finishedAt().isBefore(run.startedAt())) {
            fail(path + ".finishedAt", "before startedAt");
        }
    }

    /**
     * 취소의 완료 범위가 세 곳에서 같은가: {@code payload.lastTradingDate} = {@code
     * engineOutput.run.lastTradingDate} = 시계열 마지막 날짜.
     *
     * <p>실행에는 payload 의 값을 진행 거래일로 기록하고 결과는 {@code engineOutput} 전체를 저장하므로, 둘이 어긋나면 화면의 완료 날짜와 실제
     * 요약·시계열 범위가 다르게 보인다. 계약이 한 값을 세 군데 적어 두는 이상 여기서 대조해야 그 어긋남이 저장되지 않는다.
     */
    private static void cancelledRange(
            FinishedPayload payload,
            EngineOutput output,
            List<EngineOutput.PortfolioSnapshot> series,
            String base) {
        LocalDate stopped = require(payload.lastTradingDate(), "payload.lastTradingDate");
        if (!stopped.equals(output.run().lastTradingDate())) {
            fail(
                    base + ".run.lastTradingDate",
                    output.run().lastTradingDate()
                            + " differs from payload.lastTradingDate "
                            + stopped);
        }
        if (series.isEmpty()) {
            fail(base + ".portfolioSeries", "must not be empty when status is CANCELLED");
        }
        LocalDate last = series.get(series.size() - 1).date();
        if (!stopped.equals(last)) {
            fail(
                    base + ".portfolioSeries",
                    "last date " + last + " differs from payload.lastTradingDate " + stopped);
        }
    }

    private static void notPartial(FinishedPayload payload) {
        if (payload.partial()) {
            fail("payload.partial", "must be false when status is " + payload.status());
        }
    }

    /** COMPLETED와 CANCELLED(부분 결과) 공통: 계산된 구간의 성과·실행 집계가 있어야 한다. */
    private static void computed(EngineOutput output, String base) {
        if (output.run().error() != null) {
            fail(base + ".run.error", "must be absent when status is " + output.run().status());
        }
        EngineOutput.PerformanceResult performance =
                require(output.performance(), base + ".performance");
        require(performance.initialEquity(), base + ".performance.initialEquity");
        require(performance.finalEquity(), base + ".performance.finalEquity");
        require(performance.currency(), base + ".performance.currency");
        require(performance.maxDrawdown(), base + ".performance.maxDrawdown");
        EngineOutput.ExecutionResult execution = require(output.execution(), base + ".execution");
        require(execution.totalCommission(), base + ".execution.totalCommission");
        require(execution.totalSlippageCost(), base + ".execution.totalSlippageCost");
    }

    private static void failed(EngineOutput output, String base) {
        EngineOutput.EngineError error = require(output.run().error(), base + ".run.error");
        requireId(error.code(), base + ".run.error.code");
        requireNotBlank(error.message(), base + ".run.error.message");
        if (output.performance() != null) {
            fail(base + ".performance", "must be absent when status is FAILED");
        }
        if (output.execution() != null) {
            fail(base + ".execution", "must be absent when status is FAILED");
        }
        if (!output.portfolioSeries().isEmpty()) {
            fail(base + ".portfolioSeries", "must be empty when status is FAILED");
        }
        if (!output.trades().isEmpty()) {
            fail(base + ".trades", "must be empty when status is FAILED");
        }
        if (output.fills() != null && !output.fills().isEmpty()) {
            fail(base + ".fills", "must be absent or empty when status is FAILED");
        }
    }

    private static void snapshots(List<EngineOutput.PortfolioSnapshot> series, String base) {
        for (int i = 0; i < series.size(); i++) {
            String path = base + "[" + i + "]";
            EngineOutput.PortfolioSnapshot snapshot = require(series.get(i), path);
            require(snapshot.date(), path + ".date");
            require(snapshot.equity(), path + ".equity");
            require(snapshot.cash(), path + ".cash");
            require(snapshot.positionValue(), path + ".positionValue");
            require(snapshot.cumulativeReturn(), path + ".cumulativeReturn");
            require(snapshot.drawdown(), path + ".drawdown");
            if (snapshot.positions() != null) {
                for (int j = 0; j < snapshot.positions().size(); j++) {
                    String positionPath = path + ".positions[" + j + "]";
                    EngineOutput.PositionSnapshot position =
                            require(snapshot.positions().get(j), positionPath);
                    requireInstrumentId(position.instrumentId(), positionPath + ".instrumentId");
                    require(position.quantity(), positionPath + ".quantity");
                    require(position.marketValue(), positionPath + ".marketValue");
                }
            }
            if (snapshot.cashFlows() != null) {
                for (int j = 0; j < snapshot.cashFlows().size(); j++) {
                    String flowPath = path + ".cashFlows[" + j + "]";
                    EngineOutput.CashFlow flow = require(snapshot.cashFlows().get(j), flowPath);
                    requireNotBlank(flow.type(), flowPath + ".type");
                    require(flow.amount(), flowPath + ".amount");
                }
            }
        }
    }

    private static void trades(List<EngineOutput.TradeResult> trades, String base) {
        for (int i = 0; i < trades.size(); i++) {
            String path = base + "[" + i + "]";
            EngineOutput.TradeResult trade = require(trades.get(i), path);
            requireId(trade.tradeId(), path + ".tradeId");
            requireInstrumentId(trade.instrumentId(), path + ".instrumentId");
            require(trade.side(), path + ".side");
            require(trade.status(), path + ".status");
            require(trade.entryTime(), path + ".entryTime");
            require(trade.entryPrice(), path + ".entryPrice");
            require(trade.quantity(), path + ".quantity");
            require(trade.commission(), path + ".commission");
            require(trade.slippageCost(), path + ".slippageCost");
            if (trade.status() == TradeStatus.CLOSED) {
                require(trade.exitTime(), path + ".exitTime");
                require(trade.exitPrice(), path + ".exitPrice");
                require(trade.grossPnl(), path + ".grossPnl");
                require(trade.realizedPnl(), path + ".realizedPnl");
                require(trade.returnRate(), path + ".returnRate");
            }
        }
    }

    private static void fills(List<EngineOutput.FillResult> fills, String base) {
        for (int i = 0; i < fills.size(); i++) {
            String path = base + "[" + i + "]";
            EngineOutput.FillResult fill = require(fills.get(i), path);
            requireId(fill.fillId(), path + ".fillId");
            requireInstrumentId(fill.instrumentId(), path + ".instrumentId");
            require(fill.side(), path + ".side");
            require(fill.time(), path + ".time");
            require(fill.price(), path + ".price");
            require(fill.referencePrice(), path + ".referencePrice");
            require(fill.quantity(), path + ".quantity");
            require(fill.cashCost(), path + ".cashCost");
            EngineOutput.FillCosts costs = require(fill.costs(), path + ".costs");
            require(costs.commission(), path + ".costs.commission");
            require(costs.spread(), path + ".costs.spread");
            require(costs.slippage(), path + ".costs.slippage");
            require(costs.tax(), path + ".costs.tax");
        }
    }

    private static void warnings(List<EngineOutput.Warning> warnings, String base) {
        for (int i = 0; i < warnings.size(); i++) {
            String path = base + "[" + i + "]";
            EngineOutput.Warning warning = require(warnings.get(i), path);
            requireNotBlank(warning.code(), path + ".code");
        }
    }

    private static <T> T require(T value, String path) {
        if (value == null) {
            fail(path, "is required");
        }
        return value;
    }

    private static void requireNotBlank(String value, String path) {
        if (value == null || value.isBlank()) {
            fail(path, "must not be blank");
        }
    }

    private static void requireId(String value, String path) {
        requireNotBlank(value, path);
        if (value.length() > MAX_ID_LENGTH) {
            fail(path, "length " + value.length() + " exceeds " + MAX_ID_LENGTH);
        }
    }

    private static void requireInstrumentId(String value, String path) {
        requireNotBlank(value, path);
        if (!INSTRUMENT_ID.matcher(value).matches()) {
            fail(path, "must match " + InstrumentId.REGEX + " but was " + value);
        }
    }

    private static void fail(String path, String message) {
        throw new IllegalArgumentException("contract violation at " + path + ": " + message);
    }
}
