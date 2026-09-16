package com.ssafy.thorp.marketflow.domain.model;

import static org.assertj.core.api.Assertions.assertThat;

import com.ssafy.thorp.marketflow.domain.vo.PathPoint;
import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.List;
import org.junit.jupiter.api.Test;

class NarrativeSubjectSelectorTest {

    private final NarrativeSubjectSelector selector = new NarrativeSubjectSelector();

    @Test
    void selectsAtMostTwoSubjectsDeterministicallyWithoutComparingDifferentUnits() {
        var percentageSmall = indicator("percentage-small", "%", "1.0", "2.0", "3.0");
        var wonLarge = indicator("won-large", "원", "100", "200", "300");
        var percentageLarge = indicator("percentage-large", "%", "1.0", "4.0", "8.0");

        assertThat(selector.select(List.of(percentageSmall, wonLarge, percentageLarge)))
                .extracting(subject -> subject.indicator().indicatorId())
                .containsExactly("won-large", "percentage-large");
    }

    @Test
    void returnsEmptyWhenEveryPathHasMissingValues() {
        var missing = indicator("missing", "%", "1.0", null, "2.0");

        assertThat(selector.select(List.of(missing))).isEmpty();
    }

    @Test
    void usesRegistryOrderWhenPersistenceAndComparableMagnitudeAreTied() {
        var first = indicator("first", "%", "1.0", "2.0", "3.0");
        var second = indicator("second", "%", "5.0", "6.0", "7.0");

        assertThat(selector.select(List.of(first, second)))
                .extracting(subject -> subject.indicator().indicatorId())
                .containsExactly("first", "second");
    }

    @Test
    void ignoresReleaseAfterReferenceMonthWhenSelectingSubjects() {
        var base = indicator("base", "%", "1.0", "2.0", "3.0");
        var withLaterRelease =
                new IndicatorDisplay(
                        base.indicatorId(),
                        base.name(),
                        base.unit(),
                        base.value(),
                        base.path(),
                        base.referenceMonth(),
                        base.releaseDate(),
                        base.source(),
                        base.agency(),
                        new AfterReferenceMonthRelease(
                                YearMonth.of(2026, 9), new BigDecimal("9999"), "%"));

        var withoutRelease = selector.select(List.of(base)).getFirst();
        var afterReleaseExists = selector.select(List.of(withLaterRelease)).getFirst();

        assertThat(afterReleaseExists.indicator().indicatorId())
                .isEqualTo(withoutRelease.indicator().indicatorId());
        assertThat(afterReleaseExists.direction()).isEqualTo(withoutRelease.direction());
        assertThat(afterReleaseExists.persistence()).isEqualTo(withoutRelease.persistence());
        assertThat(afterReleaseExists.movementMonth()).isEqualTo(withoutRelease.movementMonth());
        var formatter = new NarrativeFormatter();
        assertThat(formatter.format(List.of(afterReleaseExists), true))
                .isEqualTo(formatter.format(List.of(withoutRelease), true));
    }

    private static IndicatorDisplay indicator(
            String id, String unit, String first, String second, String third) {
        return new IndicatorDisplay(
                id,
                id,
                unit,
                decimal(third),
                List.of(
                        new PathPoint(YearMonth.of(2026, 6), decimal(first)),
                        new PathPoint(YearMonth.of(2026, 7), decimal(second)),
                        new PathPoint(YearMonth.of(2026, 8), decimal(third))),
                YearMonth.of(2026, 8),
                null,
                "source",
                "agency",
                null);
    }

    private static BigDecimal decimal(String value) {
        return value == null ? null : new BigDecimal(value);
    }
}
