package com.ssafy.thorp.backtest.presentation.response;

import static org.assertj.core.api.Assertions.assertThat;

import com.ssafy.thorp.backtest.application.port.message.EngineOutput;
import com.ssafy.thorp.backtest.application.port.message.TradeSide;
import com.ssafy.thorp.backtest.application.port.message.TradeStatus;
import com.ssafy.thorp.backtest.application.result.BacktestTradePageResult;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * 거래일 변환과 OPEN 거래의 null 매핑을 고정한다.
 *
 * <p>계약 픽스처의 {@code entryTime}은 자정 UTC라 KST 09:00이 되어 <b>UTC 절단과 결과가 같다.</b> 즉 픽스처만으로는 이 변환이 맞는지
 * 확인되지 않는다. {@code 15:00Z} 이후(KST 다음날 00:00 이상) 경계를 반드시 넣는다 — 빠지면 거래소를 늘릴 때 하루씩 밀린 결과가 조용히 나간다.
 */
class BacktestTradePageResponseTest {

    private static EngineOutput.TradeResult trade(
            TradeStatus status, Instant entryTime, Instant exitTime) {
        return new EngineOutput.TradeResult(
                "TRD-000001",
                "005930.XKRX",
                TradeSide.LONG,
                status,
                entryTime,
                exitTime,
                new BigDecimal("53700.00"),
                exitTime == null ? null : new BigDecimal("52800.00"),
                new BigDecimal("90"),
                exitTime == null ? null : new BigDecimal("-81000.00"),
                new BigDecimal("1437.75"),
                new BigDecimal("4792.50"),
                exitTime == null ? null : new BigDecimal("-87230.25"),
                exitTime == null ? null : new BigDecimal("-0.018049"),
                null,
                null);
    }

    private static BacktestTradePageResponse.Trade mapped(EngineOutput.TradeResult t) {
        return BacktestTradePageResponse.from(
                        new BacktestTradePageResult("BT-1", false, 0, 100, 1, 1, List.of(t)))
                .content()
                .get(0);
    }

    @ParameterizedTest(name = "{0} → {1}")
    @CsvSource({
        // 자정 UTC = KST 09:00. UTC 절단과 결과가 같아 이 케이스만으로는 변환을 검증하지 못한다
        "2025-01-22T00:00:00Z, 2025-01-22",
        // KRX 종가 무렵(15:30 KST = 06:30Z)
        "2025-01-22T06:30:00Z, 2025-01-22",
        // 여기서 갈린다: UTC로 자르면 01-22, 거래소 현지(KST)로는 01-23
        "2025-01-22T15:00:00Z, 2025-01-23",
        "2025-01-22T23:59:59Z, 2025-01-23"
    })
    @DisplayName("체결 시각은 거래소 현지(KST) 거래일로 바뀐다 — UTC로 자르지 않는다")
    void convertsToExchangeLocalTradingDate(String instant, String expected) {
        BacktestTradePageResponse.Trade result =
                mapped(trade(TradeStatus.CLOSED, Instant.parse(instant), Instant.parse(instant)));

        assertThat(result.entryDate()).isEqualTo(LocalDate.parse(expected));
        assertThat(result.exitDate()).isEqualTo(LocalDate.parse(expected));
    }

    @Test
    @DisplayName("OPEN 거래는 청산 관련 필드가 전부 null이다")
    void openTradeHasNullExitFields() {
        BacktestTradePageResponse.Trade result =
                mapped(trade(TradeStatus.OPEN, Instant.parse("2025-05-14T00:00:00Z"), null));

        assertThat(result.exitDate()).isNull();
        assertThat(result.exitPrice()).isNull();
        assertThat(result.grossPnl()).isNull();
        assertThat(result.realizedPnl()).isNull();
        assertThat(result.returnRate()).isNull();
        // 진입 측 비용은 OPEN에도 있다
        assertThat(result.commission()).isEqualByComparingTo("1437.75");
        assertThat(result.slippageCost()).isEqualByComparingTo("4792.50");
    }

    @Test
    @DisplayName("null 시각은 null 거래일이다")
    void nullInstantMapsToNullDate() {
        assertThat(BacktestTradePageResponse.tradingDate(null)).isNull();
    }
}
