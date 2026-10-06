package com.ssafy.thorp.marketflow.testfixture;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ssafy.thorp.macro.support.MacroRegistrySeed;
import com.ssafy.thorp.marketflow.domain.vo.FieldId;
import java.time.YearMonth;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
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
    @DisplayName("표본 자료의 분야별 지표 식별자는 008이 실제로 쓰는 식별자와 같다")
    void usesTheRegistryIndicatorIds() {
        // 지어낸 식별자로 돌면 009 시험이 실제 자료에는 없는 이름으로 통과한다. 008 FR-001 표가 기준이다.
        var references = adapter.loadFieldReferenceMonths();

        assertThat(
                        references.fields().stream()
                                .collect(
                                        Collectors.toMap(
                                                field -> field.fieldId().value(),
                                                field -> field.indicatorIds())))
                .isEqualTo(MacroRegistrySeed.FIELD_INDICATORS);
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
                adapter.loadRecentPath("construction_completed_constant", YearMonth.of(2026, 7))
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
