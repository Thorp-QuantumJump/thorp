package com.ssafy.thorp.marketflow.domain.model;

import static org.assertj.core.api.Assertions.assertThat;

import com.ssafy.thorp.marketflow.domain.vo.PathPoint;
import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.List;
import org.junit.jupiter.api.Test;

class NarrativeFormatterTest {

    private final NarrativeSubjectSelector selector = new NarrativeSubjectSelector();
    private final NarrativeFormatter formatter = new NarrativeFormatter();

    @Test
    void formatsOnlyDirectionDurationNameValueAndUnit() {
        var indicator = indicator("수출 증가율", "%", "3.2", "4.0", "4.8");

        String narrative = formatter.format(selector.select(List.of(indicator)), true);

        assertThat(narrative).contains("수출 증가율", "4.8", "%", "2개월 연속", "높아졌습니다");
    }

    @Test
    void returnsMissingPathSentenceWithoutInventingMovement() {
        assertThat(formatter.format(List.of(), false)).isEqualTo("최근 3개월 경로를 확인할 수 없습니다.");
    }

    @Test
    void returnsNeutralSentenceWhenNoDirectionalSubjectExists() {
        assertThat(formatter.format(List.of(), true)).isEqualTo("최근 3개월 동안 뚜렷한 방향 변화가 없었습니다.");
    }

    @Test
    void everyFormatterOutputExcludesJudgmentForecastAndRecommendationTerms() {
        var outputs =
                List.of(
                        formatter.format(
                                selector.select(List.of(indicator("고용", "%", "3", "2", "1"))),
                                true),
                        formatter.format(List.of(), false),
                        formatter.format(List.of(), true));

        assertThat(outputs)
                .allSatisfy(
                        output ->
                                assertThat(NarrativeFormatter.findForbiddenTerms(output))
                                        .isEmpty());
    }

    private static IndicatorDisplay indicator(
            String name, String unit, String first, String second, String third) {
        return new IndicatorDisplay(
                name,
                name,
                unit,
                new BigDecimal(third),
                List.of(
                        new PathPoint(YearMonth.of(2026, 6), new BigDecimal(first)),
                        new PathPoint(YearMonth.of(2026, 7), new BigDecimal(second)),
                        new PathPoint(YearMonth.of(2026, 8), new BigDecimal(third))),
                YearMonth.of(2026, 8),
                null,
                "source",
                "agency",
                null);
    }
}
