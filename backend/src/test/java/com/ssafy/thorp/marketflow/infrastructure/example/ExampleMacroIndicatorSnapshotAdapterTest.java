package com.ssafy.thorp.marketflow.infrastructure.example;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ssafy.thorp.marketflow.domain.vo.FieldId;
import java.time.YearMonth;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

class ExampleMacroIndicatorSnapshotAdapterTest {

    private final ExampleMacroIndicatorSnapshotAdapter adapter =
            new ExampleMacroIndicatorSnapshotAdapter(
                    new ClassPathResource(ExampleMacroIndicatorSnapshotAdapter.RESOURCE_PATH),
                    new ObjectMapper());

    @Test
    void returnsAllSevenFieldsAndSeventeenIndicators() {
        var references = adapter.loadFieldReferenceMonths();

        assertThat(references.fields()).hasSize(7);
        assertThat(references.fields()).flatExtracting(field -> field.indicatorIds()).hasSize(17);
        assertThat(adapter.loadIndicatorMeta(references.registryVersion())).hasSize(17);
        assertThat(adapter.loadCollectionState())
                .hasSize(17)
                .allSatisfy(
                        state -> {
                            assertThat(state.successful()).isTrue();
                            assertThat(state.exampleData()).isTrue();
                        });
    }

    @Test
    void keepsDifferentReferenceMonthsAndMissingValue() {
        var references = adapter.loadFieldReferenceMonths();

        assertThat(references.fields())
                .filteredOn(field -> field.fieldId() == FieldId.PRODUCTION)
                .singleElement()
                .extracting(field -> field.referenceMonth())
                .isEqualTo(YearMonth.of(2026, 7));
        assertThat(references.fields())
                .filteredOn(field -> field.fieldId() == FieldId.OVERSEAS)
                .singleElement()
                .extracting(field -> field.referenceMonth())
                .isEqualTo(YearMonth.of(2026, 6));

        var construction =
                adapter.loadRecentPath("construction_completed", YearMonth.of(2026, 7))
                        .orElseThrow();
        assertThat(construction.path()).extracting(point -> point.value()).containsNull();
    }

    @Test
    void separatesReleaseAfterFieldReferenceMonthFromThreeMonthPath() {
        var unemployment =
                adapter.loadRecentPath("unemployment_rate", YearMonth.of(2026, 8)).orElseThrow();

        assertThat(unemployment.path())
                .extracting(point -> point.month())
                .containsExactly(
                        YearMonth.of(2026, 6), YearMonth.of(2026, 7), YearMonth.of(2026, 8));
        assertThat(unemployment.afterReferenceMonthRelease()).isNotNull();
        assertThat(unemployment.afterReferenceMonthRelease().referenceMonth())
                .isEqualTo(YearMonth.of(2026, 9));
    }
}
