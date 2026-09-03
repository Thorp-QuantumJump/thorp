package com.ssafy.thorp.backtest.domain.vo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ssafy.thorp.backtest.BacktestResultFixtures;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class EquityCurveTest {

    private static final LocalDate D1 = LocalDate.of(2019, 1, 2);
    private static final LocalDate D2 = LocalDate.of(2019, 1, 3);
    private static final LocalDate D3 = LocalDate.of(2019, 1, 4);

    @Test
    @DisplayName("날짜 오름차순 포인트로 생성되고 first/last/size를 제공한다")
    void createsWithAscendingDates() {
        EquityCurve curve =
                new EquityCurve(
                        List.of(
                                BacktestResultFixtures.point(D1, 100),
                                BacktestResultFixtures.point(D2, 110),
                                BacktestResultFixtures.point(D3, 105)));

        assertThat(curve.size()).isEqualTo(3);
        assertThat(curve.first().equity()).isEqualTo(100);
        assertThat(curve.last().equity()).isEqualTo(105);
    }

    @Test
    @DisplayName("빈 리스트는 거부한다")
    void rejectsEmpty() {
        assertThatThrownBy(() -> new EquityCurve(List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least one point");
    }

    @Test
    @DisplayName("날짜가 역순이거나 중복이면 거부한다")
    void rejectsNonAscendingDates() {
        assertThatThrownBy(
                        () ->
                                new EquityCurve(
                                        List.of(
                                                BacktestResultFixtures.point(D2, 100),
                                                BacktestResultFixtures.point(D1, 100))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("strictly ascending");
        assertThatThrownBy(
                        () ->
                                new EquityCurve(
                                        List.of(
                                                BacktestResultFixtures.point(D1, 100),
                                                BacktestResultFixtures.point(D1, 100))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("외부 리스트를 나중에 바꿔도 곡선은 영향을 받지 않는다")
    void copiesInputList() {
        List<EquityPoint> source = new ArrayList<>();
        source.add(BacktestResultFixtures.point(D1, 100));
        EquityCurve curve = new EquityCurve(source);

        source.add(BacktestResultFixtures.point(D2, 200));

        assertThat(curve.size()).isEqualTo(1);
        assertThatThrownBy(() -> curve.points().add(BacktestResultFixtures.point(D3, 300)))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("EquityPoint는 음수 자산·양수 drawdown·null 날짜를 거부한다")
    void pointRejectsInvalidValues() {
        assertThatThrownBy(() -> new EquityPoint(D1, -1, 0.0, 0.0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("equity");
        assertThatThrownBy(() -> new EquityPoint(D1, 100, 0.0, 0.01))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("drawdown");
        assertThatThrownBy(() -> new EquityPoint(D1, 100, Double.NaN, 0.0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cumulativeReturn");
        assertThatThrownBy(() -> new EquityPoint(null, 100, 0.0, 0.0))
                .isInstanceOf(NullPointerException.class);
    }
}
