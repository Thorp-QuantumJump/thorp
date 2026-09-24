package com.ssafy.thorp.assistant.application.service.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ssafy.thorp.assistant.application.exception.ToolUnavailableException;
import com.ssafy.thorp.assistant.application.result.Evidence;
import com.ssafy.thorp.assistant.application.result.EvidenceKind;
import com.ssafy.thorp.backtest.application.command.FindBacktestTradesQuery;
import com.ssafy.thorp.backtest.application.port.message.EngineOutput.TradeResult;
import com.ssafy.thorp.backtest.application.port.message.TradeReason;
import com.ssafy.thorp.backtest.application.port.message.TradeSide;
import com.ssafy.thorp.backtest.application.port.message.TradeStatus;
import com.ssafy.thorp.backtest.application.result.BacktestPositionsResult;
import com.ssafy.thorp.backtest.application.result.BacktestPositionsResult.Position;
import com.ssafy.thorp.backtest.application.result.BacktestTimeseriesResult;
import com.ssafy.thorp.backtest.application.result.BacktestTimeseriesResult.Point;
import com.ssafy.thorp.backtest.application.result.BacktestTradePageResult;
import com.ssafy.thorp.result.application.service.PublicResultService;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** 읽기 도구 다섯 개(T031). 고르기만 하고, 기간 밖·규칙 위반은 실행하지 않으며, 식별자·참고선 값은 근거에 없다. */
class ResultToolsTest {
    static final String TOKEN = "0123456789abcdef0123456789abcdef";
    static final LocalDate START = LocalDate.of(2025, 1, 2);
    static final LocalDate END = LocalDate.of(2025, 6, 30);

    private final PublicResultService publicResults = mock(PublicResultService.class);
    private final ResultToolContext context =
            new ResultToolContext(
                    publicResults,
                    TOKEN,
                    START,
                    END,
                    Map.of("005930.XKRX", "반도체"),
                    new BigDecimal("-0.041757"),
                    100);

    /** 거래일 점. 벤치마크·현금 흐름은 도구 근거에 나가면 안 되는 값이라 일부러 채운다. */
    static Point point(String date, String equity, String cumulative, String drawdown) {
        return new Point(
                LocalDate.parse(date),
                new BigDecimal(equity),
                new BigDecimal(cumulative),
                new BigDecimal(drawdown),
                new BigDecimal("0.123456"),
                List.of());
    }

    /** 2025-03-17(월)~03-21(금)에 03-19 가 저점인 주간 + 앞뒤. 03-20 은 휴장. */
    static List<Point> series() {
        return List.of(
                point("2025-01-02", "10000000", "0.000000", "0.000000"),
                point("2025-03-17", "10400000", "0.040000", "0.000000"),
                point("2025-03-18", "10200000", "0.020000", "-0.019231"),
                point("2025-03-19", "9965727", "-0.003427", "-0.041757"),
                point("2025-03-21", "10100000", "0.010000", "-0.028846"),
                point("2025-04-01", "10300000", "0.030000", "-0.009615"),
                point("2025-06-30", "11666521", "0.166652", "0.000000"));
    }

    void stubSeries(List<Point> points) {
        when(publicResults.getTimeseries(TOKEN))
                .thenReturn(new BacktestTimeseriesResult("BT-20260907-000001", false, points));
    }

    static String dump(List<Evidence> facts) {
        return facts.toString();
    }

    @Nested
    @DisplayName("getEquityOn")
    class EquityOn {
        private final EquityOnTool tool = new EquityOnTool(context);

        @Test
        void picksThePointOfThatDay() {
            stubSeries(series());

            List<Evidence> facts = tool.run(Map.of("date", "2025-03-19"));

            assertThat(facts).extracting(Evidence::label).containsExactly("자산가치", "누적 수익률", "하락 폭");
            assertThat(facts.get(0).value()).isEqualTo("9965727");
            assertThat(facts.get(0).kind()).isEqualTo(EvidenceKind.MONEY);
            assertThat(facts)
                    .allSatisfy(
                            fact -> assertThat(fact.asOf()).isEqualTo(LocalDate.of(2025, 3, 19)));
            assertThat(dump(facts)).doesNotContain("0.123456", "BT-", TOKEN);
        }

        @Test
        void holidayFallsBackToPreviousTradingDayAndSaysSo() {
            stubSeries(series());

            List<Evidence> facts = tool.run(Map.of("date", "2025-03-20"));

            assertThat(facts.get(0).label()).isEqualTo("자산가치 (휴장일이라 직전 거래일 값)");
            assertThat(facts.get(0).asOf()).isEqualTo(LocalDate.of(2025, 3, 19));
        }

        @Test
        void rejectsDatesOutsideThePeriodWithoutReading() {
            assertThatThrownBy(() -> tool.run(Map.of("date", "2024-12-31")))
                    .isInstanceOf(ToolUnavailableException.class)
                    .hasMessageContaining("결과 기간");
            assertThatThrownBy(() -> tool.run(Map.of("date", "2025-07-01")))
                    .isInstanceOf(ToolUnavailableException.class);
            verify(publicResults, never()).getTimeseries(any());
        }
    }

    @Nested
    @DisplayName("getEquityRange")
    class EquityRange {
        private final EquityRangeTool tool = new EquityRangeTool(context);

        @Test
        void picksFirstLastHighestLowestOnly() {
            stubSeries(series());

            List<Evidence> facts = tool.run(Map.of("from", "2025-03-01", "to", "2025-03-31"));

            assertThat(facts).hasSize(12);
            assertThat(facts)
                    .extracting(Evidence::label)
                    .contains("구간 첫날 자산가치", "구간 마지막 날 자산가치", "구간 최고일 자산가치", "구간 최저일 자산가치");
            assertThat(facts.get(0).asOf()).isEqualTo(LocalDate.of(2025, 3, 17));
            assertThat(facts.get(3).asOf()).isEqualTo(LocalDate.of(2025, 3, 21));
            assertThat(facts.get(6).asOf()).isEqualTo(LocalDate.of(2025, 3, 17));
            assertThat(facts.get(9).asOf()).isEqualTo(LocalDate.of(2025, 3, 19));
            assertThat(facts)
                    .extracting(Evidence::value)
                    .contains("10400000", "9965727")
                    .doesNotContain("0.123456");
        }

        @Test
        void rejectsReversedAndOverlongRanges() {
            assertThatThrownBy(() -> tool.run(Map.of("from", "2025-03-31", "to", "2025-03-01")))
                    .isInstanceOf(ToolUnavailableException.class)
                    .hasMessageContaining("from");
            ResultToolContext wide =
                    new ResultToolContext(
                            publicResults,
                            TOKEN,
                            LocalDate.of(2020, 1, 1),
                            LocalDate.of(2025, 12, 31),
                            Map.of(),
                            null,
                            100);
            assertThatThrownBy(
                            () ->
                                    new EquityRangeTool(wide)
                                            .run(Map.of("from", "2024-01-01", "to", "2025-06-30")))
                    .isInstanceOf(ToolUnavailableException.class)
                    .hasMessageContaining("1년");
            verify(publicResults, never()).getTimeseries(any());
        }

        @Test
        void emptyRangeIsUnavailable() {
            stubSeries(series());

            assertThatThrownBy(() -> tool.run(Map.of("from", "2025-05-01", "to", "2025-05-31")))
                    .isInstanceOf(ToolUnavailableException.class);
        }
    }

    @Nested
    @DisplayName("getTradesBetween")
    class TradesBetween {
        private final TradesBetweenTool tool = new TradesBetweenTool(context);

        TradeResult trade(String id, String entry, String exit) {
            return new TradeResult(
                    id,
                    "005930.XKRX",
                    TradeSide.LONG,
                    exit == null ? TradeStatus.OPEN : TradeStatus.CLOSED,
                    Instant.parse(entry),
                    exit == null ? null : Instant.parse(exit),
                    new BigDecimal("53700.00"),
                    exit == null ? null : new BigDecimal("52800.00"),
                    new BigDecimal("90"),
                    new BigDecimal("-81000.00"),
                    new BigDecimal("1437.75"),
                    new BigDecimal("4791.00"),
                    new BigDecimal("-87228.75"),
                    new BigDecimal("-0.018"),
                    TradeReason.SIGNAL,
                    null);
        }

        @Test
        void oneTradeIsOneFactAndFirstPageOnlyWithinCap() {
            List<TradeResult> content =
                    List.of(
                            trade("TRD-1", "2025-01-22T00:00:00Z", "2025-03-12T00:00:00Z"),
                            trade("TRD-2", "2025-04-02T00:00:00Z", null));
            when(publicResults.getTrades(eq(TOKEN), any()))
                    .thenReturn(
                            new BacktestTradePageResult(
                                    "BT-20260907-000001", false, 0, 100, 2, 1, content));

            List<Evidence> facts = tool.run(Map.of("from", "2025-01-02", "to", "2025-06-30"));

            ArgumentCaptor<FindBacktestTradesQuery> query =
                    ArgumentCaptor.forClass(FindBacktestTradesQuery.class);
            verify(publicResults).getTrades(eq(TOKEN), query.capture());
            assertThat(query.getValue().page()).isZero();
            assertThat(query.getValue().size()).isEqualTo(100);
            assertThat(query.getValue().from()).isEqualTo(LocalDate.of(2025, 1, 2));
            assertThat(facts).hasSize(2);
            assertThat(facts.get(0).label()).isEqualTo("반도체 매수 거래 (진입 2025-01-22)");
            assertThat(facts.get(0).value())
                    .contains(
                            "진입가 53,700원",
                            "수량 90주",
                            "청산 2025-03-12",
                            "청산가 52,800원",
                            "실현 손익 -87,229원");
            assertThat(facts.get(1).value()).contains("보유 중");
            assertThat(facts).allSatisfy(fact -> assertThat(fact.tab()).isEqualTo("trades"));
            assertThat(dump(facts)).doesNotContain("BT-", "TRD-", TOKEN);
        }

        @Test
        void truncatedPageLeavesAPartialMarker() {
            List<TradeResult> content = new ArrayList<>();
            for (int i = 0; i < 100; i++) {
                content.add(trade("TRD-" + i, "2025-02-03T00:00:00Z", null));
            }
            when(publicResults.getTrades(eq(TOKEN), any()))
                    .thenReturn(
                            new BacktestTradePageResult("BT-1", false, 0, 100, 240, 3, content));

            List<Evidence> facts = tool.run(Map.of("from", "2025-01-02", "to", "2025-06-30"));

            assertThat(facts).hasSize(101);
            assertThat(facts.get(100).label()).isEqualTo(TradesBetweenTool.PARTIAL_LABEL);
            assertThat(facts.get(100).value()).contains("전체 240건 중 100건");
        }

        @Test
        void rejectsOutsideAndReversed() {
            assertThatThrownBy(() -> tool.run(Map.of("from", "2024-12-01", "to", "2025-01-31")))
                    .isInstanceOf(ToolUnavailableException.class);
            assertThatThrownBy(() -> tool.run(Map.of("from", "2025-03-01", "to", "2025-02-01")))
                    .isInstanceOf(ToolUnavailableException.class);
            verify(publicResults, never()).getTrades(any(), any());
        }
    }

    @Nested
    @DisplayName("getPositionsOn")
    class PositionsOn {
        private final PositionsOnTool tool = new PositionsOnTool(context);

        @Test
        void readsHoldingsWithPreviousSnapshotNote() {
            when(publicResults.getPositions(TOKEN, LocalDate.of(2025, 3, 20)))
                    .thenReturn(
                            new BacktestPositionsResult(
                                    "BT-1",
                                    false,
                                    LocalDate.of(2025, 3, 20),
                                    LocalDate.of(2025, 3, 19),
                                    List.of(
                                            new Position(
                                                    "005930.XKRX",
                                                    new BigDecimal("90"),
                                                    new BigDecimal("4752000"),
                                                    new BigDecimal("0.4769"),
                                                    new BigDecimal("-81000")))));

            List<Evidence> facts = tool.run(Map.of("date", "2025-03-20"));

            assertThat(facts).extracting(Evidence::label).containsExactly("반도체 보유 비중 (직전 보유 기준)");
            assertThat(facts.get(0).value()).isEqualTo("0.4769");
            assertThat(facts)
                    .allSatisfy(
                            fact -> assertThat(fact.asOf()).isEqualTo(LocalDate.of(2025, 3, 19)));
        }

        @Test
        void allCashIsSaidExplicitly() {
            when(publicResults.getPositions(TOKEN, LocalDate.of(2025, 1, 2)))
                    .thenReturn(
                            new BacktestPositionsResult(
                                    "BT-1",
                                    false,
                                    LocalDate.of(2025, 1, 2),
                                    LocalDate.of(2025, 1, 2),
                                    List.of()));

            List<Evidence> facts = tool.run(Map.of("date", "2025-01-02"));

            assertThat(facts).hasSize(1);
            assertThat(facts.get(0).value()).contains("현금");
        }

        @Test
        void rejectsOutsideThePeriod() {
            assertThatThrownBy(() -> tool.run(Map.of("date", "2025-12-25")))
                    .isInstanceOf(ToolUnavailableException.class);
            verify(publicResults, never()).getPositions(any(), any());
        }
    }

    @Nested
    @DisplayName("getDeepestDrawdownWindow")
    class DeepestDrawdown {
        private final DeepestDrawdownWindowTool tool = new DeepestDrawdownWindowTool(context);

        @Test
        void picksSameDatesAsDrawdownWindowTs() {
            stubSeries(series());

            List<Evidence> facts = tool.run(Map.of());

            assertThat(facts)
                    .extracting(Evidence::label)
                    .containsExactly(
                            "하락 시작(고점) 날짜", "고점 자산가치", "가장 크게 줄어든 날짜", "저점 자산가치", "저점 하락 폭");
            assertThat(facts.get(0).value()).isEqualTo("2025-03-17");
            assertThat(facts.get(2).value()).isEqualTo("2025-03-19");
            assertThat(facts.get(4).value()).isEqualTo("-0.041757");
            assertThat(facts).allSatisfy(fact -> assertThat(fact.tab()).isEqualTo("loss"));
        }

        @Test
        void firstOccurrenceWinsWhenTied() {
            List<Point> tied = new ArrayList<>(series());
            tied.add(point("2025-05-02", "9965727", "-0.003427", "-0.041757"));
            stubSeries(tied);

            assertThat(tool.run(Map.of()).get(2).value()).isEqualTo("2025-03-19");
        }

        @Test
        void mismatchWithSummaryYieldsNothing() {
            ResultToolContext other =
                    new ResultToolContext(
                            publicResults,
                            TOKEN,
                            START,
                            END,
                            Map.of(),
                            new BigDecimal("-0.05"),
                            100);
            stubSeries(series());

            assertThat(new DeepestDrawdownWindowTool(other).run(Map.of())).isEmpty();
        }

        @Test
        void noDrawdownYieldsNothing() {
            stubSeries(
                    List.of(
                            point("2025-01-02", "1", "0", "0.000000"),
                            point("2025-06-30", "2", "1", "0.000000")));

            assertThat(tool.run(Map.of())).isEmpty();
        }
    }
}
