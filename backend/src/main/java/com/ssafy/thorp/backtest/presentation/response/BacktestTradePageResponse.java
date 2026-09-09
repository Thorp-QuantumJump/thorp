package com.ssafy.thorp.backtest.presentation.response;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.ssafy.thorp.backtest.application.port.message.EngineOutput;
import com.ssafy.thorp.backtest.application.port.message.TradeSide;
import com.ssafy.thorp.backtest.application.port.message.TradeStatus;
import com.ssafy.thorp.backtest.application.result.BacktestTradePageResult;
import com.ssafy.thorp.common.json.PlainBigDecimalSerializer;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

/**
 * {@code GET /backtests/{backtestId}/trades} 응답. 계약: {@code docs/specs/backtest-api/README.md}
 * §2.3과 {@code examples/backtest-trades.json}.
 *
 * <p>1 거래 = 포지션 라이프사이클(진입 → 청산)이다. 주문·체결 단위가 아니다.
 */
public record BacktestTradePageResponse(
        String backtestId,
        int page,
        int size,
        long totalElements,
        int totalPages,
        List<Trade> content) {

    /**
     * 체결 봉 timestamp를 거래소 현지 거래일로 바꿀 때 쓰는 타임존.
     *
     * <p>설계 OUTPUT Spec이 이 변환을 명시적으로 요구한다 — "{@code entryTime} → {@code entryDate} 변환은 체결이 발생한 봉의
     * 거래소 현지 거래일을 취한다. <b>UTC 기준으로 자르면 미국장 결과가 하루 밀리므로</b> 반드시 거래소 현지 거래일을 사용한다."
     *
     * <p>현재 모든 종목이 XKRX다(ADR-0002 D4). 거래소가 늘면 {@code instrumentId} suffix → zone 매핑으로 뺀다.
     */
    private static final ZoneId EXCHANGE = ZoneId.of("Asia/Seoul");

    /**
     * OPEN 거래는 {@code exitDate}·{@code exitPrice}·{@code grossPnl}·{@code realizedPnl}·{@code
     * returnRate}가 <b>명시적 {@code null}</b>이다(키가 있다). 설계 예시가 그렇게 적혀 있고, 0으로 대체해 표시하면 안 된다는 뜻이다. 그래서
     * 이 record에는 {@code @JsonInclude}를 붙이지 않는다.
     */
    public record Trade(
            String tradeId,
            String instrumentId,
            TradeSide side,
            TradeStatus status,
            LocalDate entryDate,
            LocalDate exitDate,
            @JsonSerialize(using = PlainBigDecimalSerializer.class) BigDecimal entryPrice,
            @JsonSerialize(using = PlainBigDecimalSerializer.class) BigDecimal exitPrice,
            @JsonSerialize(using = PlainBigDecimalSerializer.class) BigDecimal quantity,
            @JsonSerialize(using = PlainBigDecimalSerializer.class) BigDecimal grossPnl,
            @JsonSerialize(using = PlainBigDecimalSerializer.class) BigDecimal commission,
            @JsonSerialize(using = PlainBigDecimalSerializer.class) BigDecimal slippageCost,
            @JsonSerialize(using = PlainBigDecimalSerializer.class) BigDecimal realizedPnl,
            @JsonSerialize(using = PlainBigDecimalSerializer.class) BigDecimal returnRate) {}

    /** 체결 봉 timestamp → 거래소 현지 거래일. UTC로 자르지 않는다. */
    static LocalDate tradingDate(Instant instant) {
        return instant == null ? null : instant.atZone(EXCHANGE).toLocalDate();
    }

    public static BacktestTradePageResponse from(BacktestTradePageResult result) {
        return new BacktestTradePageResponse(
                result.backtestId(),
                result.page(),
                result.size(),
                result.totalElements(),
                result.totalPages(),
                result.content().stream().map(BacktestTradePageResponse::trade).toList());
    }

    private static Trade trade(EngineOutput.TradeResult t) {
        return new Trade(
                t.tradeId(),
                t.instrumentId(),
                t.side(),
                t.status(),
                tradingDate(t.entryTime()),
                tradingDate(t.exitTime()),
                t.entryPrice(),
                t.exitPrice(),
                t.quantity(),
                t.grossPnl(),
                t.commission(),
                t.slippageCost(),
                t.realizedPnl(),
                t.returnRate());
    }
}
