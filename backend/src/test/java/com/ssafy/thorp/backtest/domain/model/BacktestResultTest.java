package com.ssafy.thorp.backtest.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ssafy.thorp.backtest.BacktestResultFixtures;
import com.ssafy.thorp.backtest.domain.vo.EquityCurve;
import com.ssafy.thorp.backtest.domain.vo.PerformanceSummary;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class BacktestResultTest {

    @Test
    @DisplayName("create는 id 없이 summary·시계열을 담는다")
    void createWithoutId() {
        BacktestResult result = BacktestResultFixtures.sampleResult(1L);

        assertThat(result.getId()).isNull();
        assertThat(result.getBacktestJobId()).isEqualTo(1L);
        assertThat(result.getSummary().finalEquity())
                .isEqualTo(BacktestResultFixtures.FINAL_EQUITY);
        assertThat(result.getEquityCurve().size()).isEqualTo(20);
    }

    @Test
    @DisplayName("restore는 저장된 id를 복원하면서 같은 불변식을 검증한다")
    void restoreKeepsId() {
        BacktestResult result =
                BacktestResult.restore(
                        9L,
                        1L,
                        BacktestResultFixtures.sampleSummary(),
                        BacktestResultFixtures.sampleCurve());

        assertThat(result.getId()).isEqualTo(9L);
        assertThatThrownBy(
                        () ->
                                BacktestResult.restore(
                                        9L,
                                        1L,
                                        BacktestResultFixtures.sampleSummary(),
                                        curve(
                                                BacktestResultFixtures.INITIAL_EQUITY + 1,
                                                BacktestResultFixtures.FINAL_EQUITY)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("첫 포인트 자산이 initialEquity와 다르면 거부한다 (FR-007)")
    void rejectsFirstEquityMismatch() {
        assertThatThrownBy(
                        () ->
                                BacktestResult.create(
                                        1L,
                                        BacktestResultFixtures.sampleSummary(),
                                        curve(9_000_000L, BacktestResultFixtures.FINAL_EQUITY)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("initialEquity");
    }

    @Test
    @DisplayName("마지막 포인트 자산이 finalEquity와 다르면 거부한다 (FR-010)")
    void rejectsLastEquityMismatch() {
        assertThatThrownBy(
                        () ->
                                BacktestResult.create(
                                        1L,
                                        BacktestResultFixtures.sampleSummary(),
                                        curve(BacktestResultFixtures.INITIAL_EQUITY, 1L)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("finalEquity");
    }

    @Test
    @DisplayName("summary나 시계열이 null이면 거부한다")
    void rejectsNulls() {
        PerformanceSummary summary = BacktestResultFixtures.sampleSummary();
        EquityCurve curve = BacktestResultFixtures.sampleCurve();

        assertThatThrownBy(() -> BacktestResult.create(1L, null, curve))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> BacktestResult.create(1L, summary, null))
                .isInstanceOf(NullPointerException.class);
    }

    private static EquityCurve curve(long firstEquity, long lastEquity) {
        return new EquityCurve(
                List.of(
                        BacktestResultFixtures.point(LocalDate.of(2019, 1, 1), firstEquity),
                        BacktestResultFixtures.point(LocalDate.of(2023, 12, 31), lastEquity)));
    }
}
