package com.ssafy.thorp.backtest.domain.vo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PerformanceSummaryTest {

    private static final LocalDate PEAK = LocalDate.of(2020, 1, 1);
    private static final LocalDate TROUGH = LocalDate.of(2020, 4, 1);
    private static final LocalDate RECOVERY = LocalDate.of(2020, 7, 1);

    @Test
    @DisplayName("정상 값으로 생성된다")
    void createsWithValidValues() {
        PerformanceSummary summary =
                summary(10_000_000L, -0.1261, PEAK, TROUGH, RECOVERY, 0.6, 1.8);

        assertThat(summary.initialEquity()).isEqualTo(10_000_000L);
        assertThat(summary.maxDrawdown()).isEqualTo(-0.1261);
        assertThat(summary.maxDrawdownRecoveryDate()).isEqualTo(RECOVERY);
        assertThat(summary.metricDefinitionVersion()).isEqualTo("1.0");
    }

    @Test
    @DisplayName("지표가 전부 null(계산 불가)이어도 생성된다")
    void allowsAllMetricsUnavailable() {
        PerformanceSummary summary =
                new PerformanceSummary(
                        10_000_000L,
                        10_000_000L,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        0,
                        null,
                        null,
                        "1.0");

        assertThat(summary.totalReturn()).isNull();
        assertThat(summary.tradeCount()).isZero();
    }

    @Test
    @DisplayName("initialEquity가 0 이하이면 거부한다")
    void rejectsNonPositiveInitialEquity() {
        assertThatThrownBy(() -> summary(0L, -0.1, PEAK, TROUGH, RECOVERY, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("initialEquity");
    }

    @Test
    @DisplayName("maxDrawdown이 양수이면 거부한다")
    void rejectsPositiveMaxDrawdown() {
        assertThatThrownBy(() -> summary(10_000_000L, 0.1, PEAK, TROUGH, RECOVERY, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxDrawdown");
    }

    @Test
    @DisplayName("winRate가 0~1 범위를 벗어나면 거부한다")
    void rejectsWinRateOutOfRange() {
        assertThatThrownBy(() -> summary(10_000_000L, -0.1, PEAK, TROUGH, RECOVERY, 1.5, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("winRate");
    }

    @Test
    @DisplayName("profitFactor가 음수이면 거부한다")
    void rejectsNegativeProfitFactor() {
        assertThatThrownBy(() -> summary(10_000_000L, -0.1, PEAK, TROUGH, RECOVERY, null, -1.0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("profitFactor");
    }

    @Test
    @DisplayName("NaN·무한대 지표는 거부한다 (계산 불가는 null로 표현해야 한다)")
    void rejectsNonFiniteMetrics() {
        assertThatThrownBy(
                        () ->
                                new PerformanceSummary(
                                        1L,
                                        1L,
                                        Double.NaN,
                                        null,
                                        null,
                                        null,
                                        null,
                                        null,
                                        null,
                                        null,
                                        0,
                                        null,
                                        null,
                                        "1.0"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("totalReturn");
        assertThatThrownBy(
                        () ->
                                new PerformanceSummary(
                                        1L,
                                        1L,
                                        null,
                                        null,
                                        null,
                                        null,
                                        null,
                                        null,
                                        null,
                                        Double.POSITIVE_INFINITY,
                                        0,
                                        null,
                                        null,
                                        "1.0"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sharpeRatio");
    }

    @Test
    @DisplayName("metricDefinitionVersion이 비어 있으면 거부한다")
    void rejectsBlankVersion() {
        assertThatThrownBy(
                        () ->
                                new PerformanceSummary(
                                        1L, 1L, null, null, null, null, null, null, null, null, 0,
                                        null, null, " "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("metricDefinitionVersion");
    }

    @Test
    @DisplayName("낙폭 구간: maxDrawdown이 음수인데 고점일·저점일이 없으면 거부한다")
    void rejectsMissingPeriodWhenDrawdownNegative() {
        assertThatThrownBy(() -> summary(10_000_000L, -0.1, null, TROUGH, null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxDrawdownPeakDate");
        assertThatThrownBy(() -> summary(10_000_000L, -0.1, PEAK, null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("낙폭 구간: 고점일이 저점일보다 늦으면 거부한다")
    void rejectsPeakAfterTrough() {
        assertThatThrownBy(() -> summary(10_000_000L, -0.1, TROUGH, PEAK, null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxDrawdownPeakDate must not be after");
    }

    @Test
    @DisplayName("낙폭 구간: 회복일이 저점일 이전이거나 같으면 거부한다")
    void rejectsRecoveryNotAfterTrough() {
        assertThatThrownBy(() -> summary(10_000_000L, -0.1, PEAK, TROUGH, TROUGH, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxDrawdownRecoveryDate");
    }

    @Test
    @DisplayName("낙폭 구간: 미회복(회복일 null)은 허용한다")
    void allowsUnrecovered() {
        PerformanceSummary summary = summary(10_000_000L, -0.1, PEAK, TROUGH, null, null, null);

        assertThat(summary.maxDrawdownRecoveryDate()).isNull();
    }

    @Test
    @DisplayName("낙폭 구간: maxDrawdown이 0이면 날짜가 있으면 거부하고, 모두 null이면 허용한다")
    void requiresNoPeriodWhenNoDrawdown() {
        assertThatThrownBy(() -> summary(10_000_000L, 0.0, PEAK, TROUGH, null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must be null");

        PerformanceSummary noDrawdown = summary(10_000_000L, 0.0, null, null, null, null, null);
        assertThat(noDrawdown.maxDrawdownPeakDate()).isNull();
    }

    private static PerformanceSummary summary(
            long initialEquity,
            Double maxDrawdown,
            LocalDate peak,
            LocalDate trough,
            LocalDate recovery,
            Double winRate,
            Double profitFactor) {
        return new PerformanceSummary(
                initialEquity,
                12_000_000L,
                0.2,
                0.1,
                maxDrawdown,
                peak,
                trough,
                recovery,
                0.15,
                1.2,
                10,
                winRate,
                profitFactor,
                "1.0");
    }
}
