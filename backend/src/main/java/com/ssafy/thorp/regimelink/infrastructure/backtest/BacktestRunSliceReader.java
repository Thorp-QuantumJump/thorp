package com.ssafy.thorp.regimelink.infrastructure.backtest;

import com.ssafy.thorp.backtest.application.port.message.EngineOutput;
import com.ssafy.thorp.backtest.application.repository.BacktestResultRepository;
import com.ssafy.thorp.backtest.domain.model.BacktestRun;
import com.ssafy.thorp.backtest.domain.model.RunPlan;
import com.ssafy.thorp.regimelink.application.port.RunSlice;
import com.ssafy.thorp.regimelink.application.port.message.FillSide;
import com.ssafy.thorp.regimelink.application.port.message.RegimeLinkJob;
import com.ssafy.thorp.regimelink.application.port.message.TradeReason;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 004 저장 결과를 011 작업 메시지의 {@code inputs} 모양으로 옮긴다 (계약 "구간 입력의 출처와 제약", DEP-004-011).
 *
 * <p><b>채우지 않는다.</b> 004 가 남긴 행만 옮기고 빠진 날을 만들어 내지 않는다 — 워커가 {@code INPUT_INCOMPLETE}로 걸러 낼 기회를 남겨야
 * 하고, 여기서 메우면 없던 값이 계산에 들어간다.
 *
 * <p>004 는 일별 스냅샷 하나에 평가액·현금·보유·현금흐름을 함께 담지만 011 메시지는 넷을 따로 싣는다. 그 갈라 담기가 이 클래스의 일이다 — 보유 스냅샷은
 * <b>수량이 바뀐 날만</b> 있고 그날 보유 전체이며 수량 0 인 종목은 빠져 있다.
 */
final class BacktestRunSliceReader {

    /** 체결 시각을 거래일로 자르는 거래소 현지 시간대. 004 {@code ExecutionPolicy.EXCHANGE_ZONE}과 같다. */
    private static final ZoneId EXCHANGE_ZONE = ZoneId.of("Asia/Seoul");

    private BacktestRunSliceReader() {}

    static Optional<RunSlice> read(
            BacktestRun run,
            BacktestResultRepository results,
            LocalDate baselineDate,
            LocalDate d0,
            LocalDate lastAvailable) {
        Optional<List<EngineOutput.PortfolioSnapshot>> stored =
                results.findPortfolioSeries(run.getId());
        if (stored.isEmpty()) {
            return Optional.empty();
        }
        List<EngineOutput.PortfolioSnapshot> snapshots = stored.get();

        // 개별 체결이 없는 실행은 구간 비용을 세울 수 없다. 빈 목록으로 보정하지 않는다 —
        // "기록하지 않았다"와 "그 구간에 거래가 없었다"가 섞이면 비용 0 인 결과가 나온다.
        Optional<List<EngineOutput.FillResult>> fills =
                results.findFills(run.getId(), startOf(d0), startOf(lastAvailable.plusDays(1)));

        RegimeLinkJob.InputsSpec inputs =
                new RegimeLinkJob.InputsSpec(
                        portfolioSeries(snapshots, baselineDate, lastAvailable),
                        openingPositions(snapshots, baselineDate),
                        dailyPositions(snapshots, d0, lastAvailable),
                        trades(fills.orElse(List.of())),
                        cashFlows(snapshots, d0, lastAvailable));

        RunPlan plan = run.getPlan();
        return Optional.of(
                new RunSlice(
                        new RegimeLinkJob.RunSpec(
                                run.getBacktestId() == null ? null : run.getBacktestId().value(),
                                plan.effectiveStart(),
                                run.getInitialCash(),
                                null),
                        universe(run, plan),
                        new RegimeLinkJob.MarketDataSpec(
                                plan.dataVersion(),
                                plan.dataVersion(),
                                run.getBenchmarkInstrumentId() == null
                                        ? null
                                        : run.getBenchmarkInstrumentId().value()),
                        inputs,
                        fills.isPresent(),
                        analyticsMethod(plan)));
    }

    /**
     * 지표 산출 방식 버전.
     *
     * <p>004 는 이 이름의 값을 따로 두지 않고 {@code versions.engine}(엔진·워커 버전)이 그 역할을 한다 — 구간 지표를 낸 코드가 무엇인지를
     * 가리키는 값이 그것뿐이다. 없는 실행은 체결도 남기지 않았으므로 발행 관문에서 이미 걸린다.
     */
    private static String analyticsMethod(RunPlan plan) {
        Map<String, Object> versions = plan.versions();
        Object engine = versions == null ? null : versions.get("engine");
        return engine == null ? null : String.valueOf(engine);
    }

    private static RegimeLinkJob.UniverseSpec universe(BacktestRun run, RunPlan plan) {
        Map<String, Object> spec = plan.requestSpecOrEmpty();
        List<RegimeLinkJob.UniverseItem> items = new ArrayList<>();
        if (spec.get("items") instanceof List<?> raw) {
            for (Object element : raw) {
                if (element instanceof Map<?, ?> item
                        && item.get("instrumentId") instanceof String instrumentId) {
                    items.add(
                            new RegimeLinkJob.UniverseItem(
                                    item.get("sectorId") instanceof String sectorId
                                            ? sectorId
                                            : null,
                                    instrumentId,
                                    null));
                }
            }
        }
        if (items.isEmpty()) {
            run.getInstrumentIds()
                    .forEach(
                            id ->
                                    items.add(
                                            new RegimeLinkJob.UniverseItem(
                                                    null, id.value(), null)));
        }
        return new RegimeLinkJob.UniverseSpec(
                spec.get("mappingVersion") instanceof String version ? version : null, items);
    }

    private static List<RegimeLinkJob.PortfolioPoint> portfolioSeries(
            List<EngineOutput.PortfolioSnapshot> snapshots, LocalDate from, LocalDate to) {
        List<RegimeLinkJob.PortfolioPoint> points = new ArrayList<>();
        for (EngineOutput.PortfolioSnapshot snapshot : snapshots) {
            if (within(snapshot.date(), from, to)) {
                points.add(
                        new RegimeLinkJob.PortfolioPoint(
                                snapshot.date(), snapshot.equity(), snapshot.cash()));
            }
        }
        return points;
    }

    /**
     * {@code baselineDate} 이전 <b>마지막</b> 보유 스냅샷.
     *
     * <p>004 는 수량이 바뀌는 날마다 스냅샷을 남기므로 그 수량이 곧 기준일의 보유다. 기준일 이전에 스냅샷이 하나도 없으면 빈 목록이며, 그것도 답이다 — 시작
     * 보유가 없었다는 뜻이거나 자료가 모자란 것이고, 뒤쪽은 워커가 {@code INPUT_INCOMPLETE}로 가린다.
     */
    private static List<RegimeLinkJob.Position> openingPositions(
            List<EngineOutput.PortfolioSnapshot> snapshots, LocalDate baselineDate) {
        List<EngineOutput.PositionSnapshot> latest = null;
        for (EngineOutput.PortfolioSnapshot snapshot : snapshots) {
            if (snapshot.date().isAfter(baselineDate)) {
                break;
            }
            if (snapshot.positions() != null) {
                latest = snapshot.positions();
            }
        }
        return latest == null ? List.of() : positions(latest);
    }

    /**
     * 구간(d0~lastAvailable) 안에서 004가 남긴 보유 스냅샷을 <b>한 줄도 빼지 않고</b> 담는다 (T103, 계약 "구간 입력의 출처와 제약").
     *
     * <p>리밸런싱일로 거르지 않는다 — 기업행위(분할·분배금)로만 수량이 바뀐 날도 스냅샷이 있고, 워커는 스냅샷이 있는 날 그 수량으로 맞춘다. 빠지면 워커가 체결
     * 누적값을 그대로 쓰는데 종목 손익 항등식은 그때도 0이라 어긋남이 드러나지 않는다(T100).
     */
    static List<RegimeLinkJob.DailyPositions> dailyPositions(
            List<EngineOutput.PortfolioSnapshot> snapshots, LocalDate from, LocalDate to) {
        List<RegimeLinkJob.DailyPositions> days = new ArrayList<>();
        for (EngineOutput.PortfolioSnapshot snapshot : snapshots) {
            if (within(snapshot.date(), from, to) && snapshot.positions() != null) {
                days.add(
                        new RegimeLinkJob.DailyPositions(
                                snapshot.date(), positions(snapshot.positions())));
            }
        }
        return days;
    }

    private static List<RegimeLinkJob.Position> positions(
            List<EngineOutput.PositionSnapshot> source) {
        List<RegimeLinkJob.Position> positions = new ArrayList<>(source.size());
        for (EngineOutput.PositionSnapshot position : source) {
            positions.add(
                    new RegimeLinkJob.Position(
                            position.instrumentId(), quantity(position.quantity())));
        }
        return positions;
    }

    private static List<RegimeLinkJob.CashFlow> cashFlows(
            List<EngineOutput.PortfolioSnapshot> snapshots, LocalDate from, LocalDate to) {
        List<RegimeLinkJob.CashFlow> flows = new ArrayList<>();
        for (EngineOutput.PortfolioSnapshot snapshot : snapshots) {
            if (!within(snapshot.date(), from, to) || snapshot.cashFlows() == null) {
                continue;
            }
            for (EngineOutput.CashFlow flow : snapshot.cashFlows()) {
                flows.add(
                        new RegimeLinkJob.CashFlow(
                                snapshot.date(), flow.instrumentId(), flow.type(), flow.amount()));
            }
        }
        return flows;
    }

    private static List<RegimeLinkJob.InputTrade> trades(List<EngineOutput.FillResult> fills) {
        List<RegimeLinkJob.InputTrade> trades = new ArrayList<>(fills.size());
        for (EngineOutput.FillResult fill : fills) {
            trades.add(
                    new RegimeLinkJob.InputTrade(
                            fill.fillId(),
                            fill.time(),
                            fill.instrumentId(),
                            fill.side() == null ? null : FillSide.valueOf(fill.side().name()),
                            quantity(fill.quantity()),
                            fill.price(),
                            fill.referencePrice(),
                            costs(fill.costs()),
                            fill.cashCost(),
                            fill.reason() == null
                                    ? null
                                    : TradeReason.valueOf(fill.reason().name())));
        }
        return trades;
    }

    private static RegimeLinkJob.FillCosts costs(EngineOutput.FillCosts costs) {
        return costs == null
                ? null
                : new RegimeLinkJob.FillCosts(
                        costs.commission(), costs.spread(), costs.slippage(), costs.tax());
    }

    /** ETF 는 정수 주라 소수 수량이 나올 수 없다. 나온다면 자료가 어긋난 것이므로 반올림하지 않고 드러낸다. */
    private static long quantity(BigDecimal value) {
        return value == null ? 0L : value.stripTrailingZeros().longValueExact();
    }

    private static boolean within(LocalDate date, LocalDate from, LocalDate to) {
        return date != null && !date.isBefore(from) && !date.isAfter(to);
    }

    private static Instant startOf(LocalDate date) {
        return date.atStartOfDay(EXCHANGE_ZONE).toInstant();
    }
}
