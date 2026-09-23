package com.ssafy.thorp.myrecord.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.ssafy.thorp.backtest.application.repository.BacktestRunRepository;
import com.ssafy.thorp.myrecord.application.repository.MyRecordRepository;
import com.ssafy.thorp.myrecord.application.result.MyRecordDetail;
import com.ssafy.thorp.myrecord.application.result.MyRecordView;
import com.ssafy.thorp.myrecord.application.result.RegistryDrift;
import com.ssafy.thorp.myrecord.application.service.MyRecordMappingDriftChecker;
import com.ssafy.thorp.myrecord.application.service.MyRecordQueryService;
import com.ssafy.thorp.myrecord.application.service.MyRecordRegistryDriftChecker;
import com.ssafy.thorp.myrecord.domain.model.RecordKind;
import com.ssafy.thorp.myrecord.domain.model.RecordStatus;
import com.ssafy.thorp.myrecord.domain.vo.EntryPath;
import com.ssafy.thorp.regime.domain.vo.BaselineOrigin;
import com.ssafy.thorp.regime.domain.vo.SnapshotKey;
import com.ssafy.thorp.strategy.application.result.StrategyRegistryResult;
import com.ssafy.thorp.strategy.application.service.StrategyRegistryQueryService;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 저장된 설정값과 지금 등록표의 판정(spec 006 T028, FR-008, quickstart S6).
 *
 * <p>여기서 보는 것은 <b>판정만 하고 고치지 않는다</b>는 것이다. 범위 밖 값을 자르거나 기본값으로 바꾸면 사용자가 저장해 둔 조건이 조용히 달라지고, 그 사실이
 * 어디에도 남지 않는다. 비우고 복원을 제안하는 것은 003 화면의 몫이다.
 */
class RegistryDriftTest {

    private static final String STRATEGY = "SECTOR_MOMENTUM";
    private static final Instant NOW = Instant.parse("2026-09-21T02:00:00Z");

    @Test
    @DisplayName("등록표가 그대로면 범위 밖이 아니다")
    void 범위_안이면_알리지_않는다() {
        Map<String, Object> config = config(Map.of("lookbackDays", 120, "topN", 3));

        Optional<RegistryDrift> drift =
                checker(axes(range(20, 250), choices(1, 2, 3))).check(config);

        assertThat(drift).isPresent();
        assertThat(drift.get().outOfRange()).isFalse();
        assertThat(drift.get().fields()).isEmpty();
    }

    @Test
    @DisplayName("범위가 좁아진 축이 fields 에 오고 저장값은 원문 그대로다")
    void 범위가_좁아지면_그_축만_알린다() {
        Map<String, Object> config = config(Map.of("lookbackDays", 240, "topN", 3));

        // 저장 뒤 상한이 250 에서 120 으로 좁아졌다. topN 은 그대로다.
        Optional<RegistryDrift> drift =
                checker(axes(range(20, 120), choices(1, 2, 3))).check(config);

        assertThat(drift).isPresent();
        assertThat(drift.get().outOfRange()).isTrue();
        assertThat(drift.get().fields()).hasSize(1);
        RegistryDrift.Field field = drift.get().fields().get(0);
        assertThat(field.axis()).isEqualTo("lookbackDays");
        assertThat(field.savedValue()).as("자르거나 기본값으로 바꾸지 않는다").isEqualTo(240);
        assertThat(field.currentRange()).containsEntry("min", 20).containsEntry("max", 120);
    }

    @Test
    @DisplayName("목록에서 사라진 후보도 범위 밖이다")
    void 후보가_사라지면_알린다() {
        Map<String, Object> config = config(Map.of("lookbackDays", 120, "topN", 5));

        Optional<RegistryDrift> drift =
                checker(axes(range(20, 250), choices(1, 2, 3))).check(config);

        assertThat(drift).isPresent();
        assertThat(drift.get().fields())
                .singleElement()
                .satisfies(
                        field -> {
                            assertThat(field.axis()).isEqualTo("topN");
                            assertThat(field.savedValue()).isEqualTo(5);
                            assertThat(field.currentRange()).containsKey("choices");
                        });
    }

    @Test
    @DisplayName("전략이 등록표에서 사라졌으면 판정하지 않는다 — 모르는 것과 괜찮은 것을 섞지 않는다")
    void 근거가_없으면_판정하지_않는다() {
        StrategyRegistryQueryService registry = mock(StrategyRegistryQueryService.class);
        when(registry.query(any()))
                .thenReturn(new StrategyRegistryResult("REG-2", false, null, List.of()));

        assertThat(new MyRecordRegistryDriftChecker(registry).check(config(Map.of("topN", 3))))
                .isEmpty();
    }

    @Test
    @DisplayName("경제환경 기록은 '설정 바꿔 비교'를 열지 않는다")
    void 경제환경_기록은_열_수_없다() {
        MyRecordRepository records = mock(MyRecordRepository.class);
        when(records.findView(anyLong(), anyString())).thenReturn(Optional.of(regimeView()));

        MyRecordDetail detail = queryService(records).detail(7L, "MR-20260921-000001");

        assertThat(detail.reopenSettings().available()).isFalse();
        assertThat(detail.reopenPayload()).as("열 수 없는 기록에는 인계할 조건도 없다").isNull();
        assertThat(detail.registryDrift()).isNull();
    }

    // -- 조립 -------------------------------------------------------------------------

    private MyRecordQueryService queryService(MyRecordRepository records) {
        return new MyRecordQueryService(
                records,
                new MyRecordMappingDriftChecker(version -> Optional.empty()),
                checker(axes(range(20, 250), choices(1, 2, 3))),
                mock(BacktestRunRepository.class),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static MyRecordRegistryDriftChecker checker(
            List<StrategyRegistryResult.AxisView> axes) {
        StrategyRegistryQueryService registry = mock(StrategyRegistryQueryService.class);
        when(registry.query(any()))
                .thenReturn(
                        new StrategyRegistryResult(
                                "REG-2",
                                false,
                                null,
                                List.of(
                                        new StrategyRegistryResult.StrategyView(
                                                STRATEGY,
                                                "섹터 모멘텀",
                                                null,
                                                "COMPUTED",
                                                true,
                                                null,
                                                0,
                                                axes,
                                                List.of(),
                                                null))));
        return new MyRecordRegistryDriftChecker(registry);
    }

    private static Map<String, Object> config(Map<String, Object> parameters) {
        return Map.of("strategyId", STRATEGY, "parameters", parameters);
    }

    private static List<StrategyRegistryResult.AxisView> axes(
            StrategyRegistryResult.RangeView lookback,
            List<StrategyRegistryResult.ChoiceView> topN) {
        return List.of(
                axis("lookbackDays", StrategyRegistryResult.InputType.NUMBER, null, lookback),
                axis("topN", StrategyRegistryResult.InputType.CHOICE, topN, null));
    }

    private static StrategyRegistryResult.AxisView axis(
            String name,
            StrategyRegistryResult.InputType type,
            List<StrategyRegistryResult.ChoiceView> choices,
            StrategyRegistryResult.RangeView range) {
        return new StrategyRegistryResult.AxisView(
                name, name, null, null, type, choices, range, null, null, null);
    }

    private static StrategyRegistryResult.RangeView range(Object min, Object max) {
        return new StrategyRegistryResult.RangeView(min, max, 10);
    }

    private static List<StrategyRegistryResult.ChoiceView> choices(Object... values) {
        return java.util.Arrays.stream(values)
                .map(
                        value ->
                                new StrategyRegistryResult.ChoiceView(
                                        value, String.valueOf(value), true))
                .toList();
    }

    private static MyRecordView regimeView() {
        return new MyRecordView(
                1L,
                "MR-20260921-000001",
                RecordKind.REGIME_SEARCH,
                "고용환경 2026-08",
                List.of(),
                List.of(),
                null,
                NOW,
                null,
                RecordStatus.THRESHOLD_APPLIED,
                null,
                null,
                null,
                new MyRecordView.Regime(
                        SnapshotKey.parse("FIELD:LABOR:2026-08-01:MACRO-1:REG-1:REGIME-V1"),
                        BaselineOrigin.T_G,
                        EntryPath.MARKET_FLOW,
                        false),
                null,
                false);
    }
}
