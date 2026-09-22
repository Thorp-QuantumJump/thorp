package com.ssafy.thorp.marketflow.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ssafy.thorp.marketflow.domain.vo.PathPoint;
import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 자료가 없을 때의 모양 (T067, 009 contracts).
 *
 * <p>008 이 어느 분야의 기준월을 아직 내지 못하면 그 분야 지표에는 그릴 달이 없다. 그때 달을 지어내 값 없는 세 점을 만들면 "자료가 아직 없다"와 "그 달의 값이
 * 결측이다"가 화면에서 같아 보인다 — 둘은 사용자가 할 일이 다르다.
 */
@DisplayName("지표 표시 항목")
class IndicatorDisplayTest {

    @Test
    @DisplayName("기준월이 없으면 경로도 없다")
    void noReferenceMonthMeansNoPath() {
        IndicatorDisplay display = display(null, List.of());
        assertThat(display.referenceMonth()).isNull();
        assertThat(display.path()).isEmpty();
    }

    @Test
    @DisplayName("기준월이 없는데 경로가 있으면 거부한다")
    void refusesPathWithoutReferenceMonth() {
        assertThatThrownBy(() -> display(null, List.of(new PathPoint(YearMonth.of(2026, 7), null))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("기준월이 없으면");
    }

    @Test
    @DisplayName("기준월이 있으면 경로는 정확히 세 점이다")
    void requiresThreePointsWhenMonthIsKnown() {
        // 부분 경로를 허용하면 두 달이 온 것인지 한 달이 빠진 것인지 구분되지 않는다.
        assertThatThrownBy(
                        () ->
                                display(
                                        YearMonth.of(2026, 7),
                                        List.of(new PathPoint(YearMonth.of(2026, 7), null))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("세 점");
    }

    @Test
    @DisplayName("값이 없는 달은 세 점 안에 값 없는 점으로 남는다")
    void keepsMissingMonthsAsEmptyPoints() {
        IndicatorDisplay display =
                display(
                        YearMonth.of(2026, 7),
                        List.of(
                                new PathPoint(YearMonth.of(2026, 5), new BigDecimal("1.1")),
                                new PathPoint(YearMonth.of(2026, 6), null),
                                new PathPoint(YearMonth.of(2026, 7), new BigDecimal("1.5"))));
        assertThat(display.path()).hasSize(3);
        assertThat(display.path().get(1).value()).isNull();
    }

    @Test
    @DisplayName("기준월이 없으면 기준월 이후 발표도 없다")
    void refusesLaterReleaseWithoutReferenceMonth() {
        assertThatThrownBy(
                        () ->
                                new IndicatorDisplay(
                                        "unemployment_rate",
                                        "실업률",
                                        "%",
                                        null,
                                        List.of(),
                                        null,
                                        null,
                                        "국가통계포털(KOSIS)",
                                        "통계청",
                                        new AfterReferenceMonthRelease(
                                                YearMonth.of(2026, 8), new BigDecimal("2.1"), "%")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("기준월 이후 발표도 없어야");
    }

    private static IndicatorDisplay display(YearMonth referenceMonth, List<PathPoint> path) {
        return new IndicatorDisplay(
                "unemployment_rate",
                "실업률",
                "%",
                null,
                path,
                referenceMonth,
                null,
                "국가통계포털(KOSIS)",
                "통계청",
                null);
    }
}
