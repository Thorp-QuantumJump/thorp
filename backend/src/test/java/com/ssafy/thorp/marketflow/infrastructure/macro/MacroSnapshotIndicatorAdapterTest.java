package com.ssafy.thorp.marketflow.infrastructure.macro;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.ssafy.thorp.macro.application.result.MacroSnapshotResult;
import com.ssafy.thorp.macro.application.result.MacroSnapshotResult.CollectionState;
import com.ssafy.thorp.macro.application.result.MacroSnapshotResult.FieldSummary;
import com.ssafy.thorp.macro.application.result.MacroSnapshotResult.IndicatorSummary;
import com.ssafy.thorp.macro.application.result.MacroSnapshotResult.ObservationValue;
import com.ssafy.thorp.macro.application.service.MacroSnapshotQueryService;
import com.ssafy.thorp.marketflow.domain.model.FieldReferenceSnapshot;
import com.ssafy.thorp.marketflow.domain.model.IndicatorCollectionState;
import com.ssafy.thorp.marketflow.domain.model.IndicatorMetadata;
import com.ssafy.thorp.marketflow.domain.model.IndicatorPathSnapshot;
import com.ssafy.thorp.marketflow.domain.vo.FieldId;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 008 스냅샷이 시장 흐름 모델로 옮겨지는지 본다 (T064).
 *
 * <p>여기서 보는 것은 값의 정확성이 아니라 <b>옮기는 과정에서 없는 것이 있는 것처럼 되지 않는가</b>다. 008 계약과 009 모델은 모양이 달라서, 빈 경로·기준월
 * 없음·중단된 지표 같은 경계가 옮겨지며 조용히 채워지기 쉽다.
 */
@DisplayName("008 스냅샷 → 시장 흐름 어댑터")
class MacroSnapshotIndicatorAdapterTest {

    private static final String REGISTRY = "macro-calc-v1";
    private static final String DATASET = "macro-20260921T131117";

    @Test
    @DisplayName("분야 기준월과 지표별 최신월을 옮긴다")
    void mapsFieldReferenceMonths() {
        FieldReferenceSnapshot snapshot = adapter(snapshot()).loadFieldReferenceMonths();

        assertThat(snapshot.registryVersion()).isEqualTo(REGISTRY);
        assertThat(snapshot.datasetVersion()).isEqualTo(DATASET);
        assertThat(snapshot.fields()).hasSize(1);
        var field = snapshot.fields().getFirst();
        assertThat(field.fieldId()).isEqualTo(FieldId.PRODUCTION);
        assertThat(field.referenceMonth()).isEqualTo(YearMonth.of(2026, 7));
        assertThat(field.indicatorIds()).containsExactly("all_industry_production", "no_data");
        assertThat(field.heldBackBy()).containsExactly("all_industry_production");
        // 값이 없는 지표는 최신월 지도에 넣지 않는다. 없는 달을 적으면 최신인 것처럼 보인다.
        assertThat(field.latestMonths()).containsOnlyKeys("all_industry_production");
    }

    @Test
    @DisplayName("경로 세 점과 발표일을 옮긴다")
    void mapsRecentPath() {
        Optional<IndicatorPathSnapshot> path =
                adapter(snapshot())
                        .loadRecentPath("all_industry_production", YearMonth.of(2026, 7));

        assertThat(path).isPresent();
        assertThat(path.get().path())
                .extracting(point -> point.month().toString())
                .containsExactly("2026-05", "2026-06", "2026-07");
        assertThat(path.get().path().getLast().value()).isEqualByComparingTo("1.5");
        assertThat(path.get().releaseDate()).isEqualTo(LocalDate.of(2026, 8, 31));
    }

    @Test
    @DisplayName("결측 달은 값 없는 점으로 남는다")
    void keepsMissingMonthsAsEmptyPoints() {
        Optional<IndicatorPathSnapshot> path =
                adapter(snapshot())
                        .loadRecentPath("all_industry_production", YearMonth.of(2026, 7));

        // 배열에서 지우면 세 달이 두 달로 보이고 어느 달이 빠졌는지 알 수 없다(원칙 VI).
        assertThat(path.orElseThrow().path().get(1).value()).isNull();
    }

    @Test
    @DisplayName("다른 기준월을 물으면 비어 있다")
    void returnsEmptyForAnotherReferenceMonth() {
        assertThat(
                        adapter(snapshot())
                                .loadRecentPath("all_industry_production", YearMonth.of(2026, 6)))
                .isEmpty();
    }

    @Test
    @DisplayName("경로가 세 달이 아니면 부분 경로를 내보내지 않는다")
    void refusesPartialPath() {
        // 중단된 지표와 기준월 없는 지표는 008이 빈 경로를 준다. 그대로 옮기면 화면이 요구하는
        // 세 점을 채우지 못해 터지고, 억지로 채우면 없는 값이 생긴다.
        assertThat(adapter(snapshot()).loadRecentPath("no_data", YearMonth.of(2026, 7))).isEmpty();
    }

    @Test
    @DisplayName("표시 메타는 변환 뒤 단위를 쓴다")
    void mapsMetadataWithTransformedUnit() {
        List<IndicatorMetadata> metadata = adapter(snapshot()).loadIndicatorMeta(REGISTRY);

        assertThat(metadata).hasSize(2);
        IndicatorMetadata first = metadata.getFirst();
        assertThat(first.name()).isEqualTo("전산업생산");
        // 원단위(2020=100)를 쓰면 증가율에 지수 단위가 붙는다.
        assertThat(first.unit()).isEqualTo("%");
        assertThat(first.source()).isEqualTo("국가통계포털(KOSIS)");
        assertThat(first.agency()).isEqualTo("통계청");
        assertThat(first.fieldId()).isEqualTo(FieldId.PRODUCTION);
    }

    @Test
    @DisplayName("다른 등록표 버전을 물으면 빈 목록이다")
    void returnsNoMetadataForAnotherRegistryVersion() {
        assertThat(adapter(snapshot()).loadIndicatorMeta("macro-calc-v0")).isEmpty();
    }

    @Test
    @DisplayName("이번 수집에서 실패한 지표는 성공이 아니다")
    void marksFailedIndicatorsUnsuccessful() {
        List<IndicatorCollectionState> states = adapter(snapshot()).loadCollectionState();

        assertThat(states).hasSize(2);
        assertThat(states.getFirst().indicatorId()).isEqualTo("all_industry_production");
        assertThat(states.getFirst().successful()).isTrue();
        assertThat(states.getFirst().exampleData()).isFalse();
        // 값이 남아 있어도 오늘 못 받았다는 사실은 숨기지 않는다(FR-011).
        assertThat(states.getLast().successful()).isFalse();
    }

    @Test
    @DisplayName("예시 자료 표시가 그대로 전달된다")
    void carriesExampleFlag() {
        MacroSnapshotResult example =
                new MacroSnapshotResult(
                        DATASET,
                        REGISTRY,
                        "macro-display-v1",
                        true,
                        LocalDate.of(2026, 7, 1),
                        collection(),
                        List.of(productionField()),
                        List.of(readyIndicator(), noDataIndicator()));

        assertThat(adapter(example).loadCollectionState())
                .allSatisfy(state -> assertThat(state.exampleData()).isTrue());
    }

    @Test
    @DisplayName("발행된 자료 버전이 없으면 무엇을 해야 하는지 말한다")
    void saysWhatToDoWhenNothingIsPublished() {
        MacroSnapshotResult noData =
                new MacroSnapshotResult(
                        null,
                        REGISTRY,
                        "macro-display-v1",
                        false,
                        null,
                        collection(),
                        List.of(),
                        List.of());

        // 빈 화면을 그리면 "자료가 아직 없다"와 "자료를 못 읽었다"가 같아 보인다.
        assertThatThrownBy(() -> adapter(noData).loadFieldReferenceMonths())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ingest_macro.py");
    }

    private static MacroSnapshotIndicatorAdapter adapter(MacroSnapshotResult result) {
        MacroSnapshotQueryService queryService = mock(MacroSnapshotQueryService.class);
        when(queryService.getSnapshot()).thenReturn(result);
        return new MacroSnapshotIndicatorAdapter(queryService);
    }

    private static MacroSnapshotResult snapshot() {
        return new MacroSnapshotResult(
                DATASET,
                REGISTRY,
                "macro-display-v1",
                false,
                LocalDate.of(2026, 7, 1),
                collection(),
                List.of(productionField()),
                List.of(readyIndicator(), noDataIndicator()));
    }

    private static CollectionState collection() {
        return new CollectionState(
                "SUCCEEDED",
                List.of(),
                Instant.parse("2026-09-21T13:11:17Z"),
                Instant.parse("2026-09-21T13:11:17Z"),
                List.of("no_data"),
                false);
    }

    private static FieldSummary productionField() {
        return new FieldSummary(
                "production",
                "생산",
                1,
                "전산업·광공업·서비스업 생산 지수",
                LocalDate.of(2026, 7, 1),
                List.of("all_industry_production"),
                List.of("all_industry_production", "no_data"));
    }

    private static IndicatorSummary readyIndicator() {
        return new IndicatorSummary(
                "all_industry_production",
                "production",
                "전산업생산",
                1,
                "국가통계포털(KOSIS)",
                "통계청",
                "2020=100",
                "%",
                "YOY_GROWTH",
                "READY",
                LocalDate.of(2026, 7, 1),
                new ObservationValue(
                        LocalDate.of(2026, 7, 1),
                        new BigDecimal("112.3"),
                        new BigDecimal("1.5"),
                        LocalDate.of(2026, 8, 31)),
                new ObservationValue(
                        LocalDate.of(2026, 7, 1),
                        new BigDecimal("112.3"),
                        new BigDecimal("1.5"),
                        LocalDate.of(2026, 8, 31)),
                List.of(
                        point(LocalDate.of(2026, 5, 1), new BigDecimal("1.1")),
                        point(LocalDate.of(2026, 6, 1), null),
                        point(LocalDate.of(2026, 7, 1), new BigDecimal("1.5"))),
                List.of(),
                Instant.parse("2026-09-21T13:11:17Z"),
                null);
    }

    /** 값을 못 받은 지표. 008은 기준월도 경로도 비워 보낸다. */
    private static IndicatorSummary noDataIndicator() {
        return new IndicatorSummary(
                "no_data",
                "production",
                "표본 없음",
                2,
                "국가통계포털(KOSIS)",
                "통계청",
                "2020=100",
                "%",
                "YOY_GROWTH",
                "COLLECTION_FAILED",
                null,
                null,
                null,
                List.of(),
                List.of(),
                null,
                null);
    }

    private static MacroSnapshotResult.PathPoint point(LocalDate month, BigDecimal value) {
        return new MacroSnapshotResult.PathPoint(
                month, null, value, null, value == null ? "NOT_TRANSFORMABLE" : null);
    }
}
