package com.ssafy.thorp.assistant.application.service.tools;

import com.ssafy.thorp.assistant.application.port.AssistantTool;
import com.ssafy.thorp.assistant.application.result.Evidence;
import com.ssafy.thorp.assistant.application.result.EvidenceKind;
import com.ssafy.thorp.assistant.application.result.ToolSpec;
import com.ssafy.thorp.backtest.application.command.FindBacktestTradesQuery;
import com.ssafy.thorp.backtest.application.port.message.EngineOutput.TradeResult;
import com.ssafy.thorp.backtest.application.port.message.TradeSide;
import com.ssafy.thorp.backtest.application.port.message.TradeStatus;
import com.ssafy.thorp.backtest.application.result.BacktestTradePageResult;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 구간의 거래 첫 페이지(계약 §2.2). 거래 1건 = 근거 1개. 페이지가 잘렸으면 "일부 거래" 근거를 하나 더 둔다.
 *
 * <p>날짜는 005 거래 응답과 같이 거래소 현지(Asia/Seoul) 기준이다.
 */
public class TradesBetweenTool implements AssistantTool {
    static final String NAME = "getTradesBetween";
    static final String PARTIAL_LABEL = "거래 읽은 범위";
    private static final ZoneId EXCHANGE = ZoneId.of("Asia/Seoul");
    private final ResultToolContext context;

    public TradesBetweenTool(ResultToolContext context) {
        this.context = context;
    }

    @Override
    public ToolSpec spec() {
        return new ToolSpec(NAME, "구간에 진입한 거래 목록(첫 페이지). 많으면 구간을 좁혀 다시 요청", List.of("from", "to"));
    }

    @Override
    public List<Evidence> run(Map<String, String> arguments) {
        LocalDate from = context.requireInPeriod(LocalDate.parse(arguments.get("from")), "from");
        LocalDate to = context.requireInPeriod(LocalDate.parse(arguments.get("to")), "to");
        context.requireOrdered(from, to);
        BacktestTradePageResult page =
                context.publicResults()
                        .getTrades(
                                context.token(),
                                new FindBacktestTradesQuery(
                                        0, context.tradesPageSize(), null, null, from, to));
        List<Evidence> facts = new ArrayList<>();
        for (TradeResult trade : page.content()) {
            LocalDate entered = localDate(trade.entryTime());
            String label =
                    context.label(trade.instrumentId())
                            + " "
                            + side(trade.side())
                            + " 거래 (진입 "
                            + entered
                            + ")";
            facts.add(
                    new Evidence(
                            null, label, describe(trade), entered, "trades", EvidenceKind.TEXT));
        }
        if (page.totalElements() > page.content().size()) {
            facts.add(
                    new Evidence(
                            null,
                            PARTIAL_LABEL,
                            "일부 거래 (전체 "
                                    + page.totalElements()
                                    + "건 중 "
                                    + page.content().size()
                                    + "건)",
                            null,
                            "trades",
                            EvidenceKind.TEXT));
        }
        return facts;
    }

    private static String describe(TradeResult trade) {
        StringBuilder text =
                new StringBuilder()
                        .append("진입가 ")
                        .append(ResultToolContext.plain(trade.entryPrice()))
                        .append("원, 수량 ")
                        .append(ResultToolContext.plain(trade.quantity()))
                        .append("주");
        if (trade.status() == TradeStatus.CLOSED) {
            text.append(", 청산 ")
                    .append(localDate(trade.exitTime()))
                    .append(" 청산가 ")
                    .append(ResultToolContext.plain(trade.exitPrice()))
                    .append("원, 실현 손익 ")
                    .append(ResultToolContext.plain(trade.realizedPnl()))
                    .append("원");
        } else {
            text.append(", 보유 중");
        }
        return text.toString();
    }

    private static String side(TradeSide side) {
        return side == TradeSide.SHORT ? "매도" : "매수";
    }

    private static LocalDate localDate(Instant instant) {
        return instant == null ? null : instant.atZone(EXCHANGE).toLocalDate();
    }
}
