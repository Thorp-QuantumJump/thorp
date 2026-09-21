package com.ssafy.thorp.regime.infrastructure.macro;

import static org.assertj.core.api.Assertions.assertThat;

import com.ssafy.thorp.macro.application.repository.MacroSnapshotRepository;
import com.ssafy.thorp.regime.application.port.FieldRegistration;
import com.ssafy.thorp.regime.application.port.IndicatorRegistration;
import com.ssafy.thorp.regime.application.port.MacroIndicatorPort;
import com.ssafy.thorp.regime.infrastructure.macro.fixture.FixtureMacroIndicatorAdapter;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * {@code MacroIndicatorPort} 계약 시험 (계약 §2·§5, tasks.md T084).
 *
 * <p><b>구현체마다 돈다.</b> 지금은 임시 대역 하나뿐이고, 008이 완성되면 {@link #implementations()}에 한 줄을 더하는 것으로 같은 시험이 실제
 * 구현체에도 돈다 — 교체 시점에 "010 로직은 그대로여야 한다"를 확인하는 자리가 이것이다.
 */
@DisplayName("지표 등록표 포트 계약")
class MacroIndicatorPortContractTest {

    static Stream<MacroIndicatorPort> implementations() {
        // 대역과 실제 구현이 같은 시험을 통과해야 한다. 대역을 지우는 것은 그다음이다(T071).
        return Stream.of(
                new FixtureMacroIndicatorAdapter(),
                new MacroRegistryIndicatorAdapter(new StubMacroRegistry()));
    }

    @ParameterizedTest
    @MethodSource("implementations")
    @DisplayName("등록표 버전을 돌려준다")
    void returnsRegistryVersion(MacroIndicatorPort port) {
        assertThat(port.registryVersion()).isNotBlank();
    }

    @ParameterizedTest
    @MethodSource("implementations")
    @DisplayName("분야가 표시 순서대로 온다")
    void returnsFieldsInDisplayOrder(MacroIndicatorPort port) {
        List<FieldRegistration> fields = port.fields();
        assertThat(fields).isNotEmpty();
        assertThat(fields).isSortedAccordingTo(FieldRegistration.DISPLAY_ORDER);
        assertThat(fields).extracting(FieldRegistration::name).doesNotContainNull();
    }

    @ParameterizedTest
    @MethodSource("implementations")
    @DisplayName("분야 id가 겹치지 않는다")
    void fieldIdsAreUnique(MacroIndicatorPort port) {
        assertThat(port.fields()).extracting(FieldRegistration::id).doesNotHaveDuplicates();
    }

    @ParameterizedTest
    @MethodSource("implementations")
    @DisplayName("등록되지 않은 지표는 비어 있다")
    void unknownIndicatorIsEmpty(MacroIndicatorPort port) {
        assertThat(port.indicator("NO_SUCH_INDICATOR")).isEmpty();
        assertThat(port.indicator("")).isEmpty();
    }

    @ParameterizedTest
    @MethodSource("implementations")
    @DisplayName("조회 실패를 이름 없는 대체물로 바꾸지 않는다")
    void doesNotFabricateNames(MacroIndicatorPort port) {
        // "(알 수 없음)" 같은 문구를 만들면 등록표에서 빠진 지표와 오타 난 id 가 화면에서 같아
        // 보이고, 그 차이를 되짚을 곳이 사라진다.
        assertThat(port.indicator("NO_SUCH_INDICATOR")).isEmpty();
    }

    @ParameterizedTest
    @MethodSource("implementations")
    @DisplayName("등록된 지표는 이름과 단위를 모두 갖는다")
    void knownIndicatorHasNameAndUnit(MacroIndicatorPort port) {
        // 어느 지표를 담는지는 구현체마다 다르다. 그래서 지표 목록을 여기 적어 두지 않고
        // 구현체가 골라 준 하나로 이름·단위가 함께 오는지만 본다.
        IndicatorRegistration any = anyIndicator(port);
        assertThat(any.name()).isNotBlank();
        assertThat(any.unit()).isNotBlank();
    }

    private static IndicatorRegistration anyIndicator(MacroIndicatorPort port) {
        if (port instanceof FixtureMacroIndicatorAdapter) {
            return port.indicator("UNEMPLOYMENT_RATE").orElseThrow();
        }
        if (port instanceof MacroRegistryIndicatorAdapter) {
            // 008 은 소문자 id 를 쓴다. 대역의 대문자 표기와 다른 것이 정상이며, 어느 쪽도
            // 상대에게 맞추지 않는다 — 010 은 포트가 준 id 를 그대로 들고 다닌다(계약 §2).
            return port.indicator("unemployment_rate").orElseThrow();
        }
        throw new IllegalStateException(
                "새 구현체를 더했으면 이 시험이 쓸 지표 하나를 고르는 자리도 함께 고친다: " + port.getClass().getSimpleName());
    }

    /**
     * 008 등록표가 발행된 상태를 흉내 낸다. 값은 2026-09-21 운영 적재의 것을 줄여 옮겼다.
     *
     * <p>관측을 돌려주지 않는 이유는 이 포트가 관측을 읽지 않기 때문이다 — 읽으면 여기서 터진다.
     */
    private static final class StubMacroRegistry implements MacroSnapshotRepository {

        @Override
        public Optional<CurrentPointer> findCurrentPointer() {
            return Optional.of(
                    new CurrentPointer(
                            1L,
                            "macro-20260921T131117",
                            2L,
                            "macro-calc-v1",
                            2L,
                            "macro-display-v1",
                            false));
        }

        @Override
        public List<IndicatorDefinitionRow> findDefinitions(long registryVersionId) {
            return List.of(
                    definition("all_industry_production", "production", "%"),
                    definition("unemployment_rate", "labor", "%"),
                    definition("trade_balance", "external_trade", "미국 달러"));
        }

        @Override
        public List<DisplayFieldRow> findDisplayFields(long displayVersionId) {
            return List.of(
                    new DisplayFieldRow("production", "생산", 1, null),
                    new DisplayFieldRow("labor", "노동", 4, null),
                    new DisplayFieldRow("external_trade", "대외거래", 3, null));
        }

        @Override
        public List<DisplayIndicatorRow> findDisplayIndicators(long displayVersionId) {
            return List.of(
                    new DisplayIndicatorRow("all_industry_production", "전산업생산", 1),
                    new DisplayIndicatorRow("unemployment_rate", "실업률", 11),
                    new DisplayIndicatorRow("trade_balance", "무역수지", 9));
        }

        @Override
        public List<BaselineRow> findBaselines(long datasetVersionId, long registryVersionId) {
            throw unexpected("기준월");
        }

        @Override
        public List<LatestMonthRow> findLatestValidMonths(long datasetVersionId) {
            throw unexpected("최신 확보월");
        }

        @Override
        public List<ObservationRow> findObservations(
                long datasetVersionId, Collection<MonthKey> keys) {
            throw unexpected("관측값");
        }

        @Override
        public Optional<CollectionStateRow> findLatestCollectionState() {
            throw unexpected("수집 상태");
        }

        private static IndicatorDefinitionRow definition(
                String indicatorId, String fieldId, String transformedUnit) {
            return new IndicatorDefinitionRow(
                    indicatorId,
                    fieldId,
                    "국가통계포털(KOSIS)",
                    "통계청",
                    "2020=100",
                    transformedUnit,
                    "YOY_GROWTH",
                    "VERIFIED",
                    "ALLOWED",
                    null);
        }

        private static AssertionError unexpected(String what) {
            return new AssertionError("등록표 포트가 " + what + " 을 읽었다. 이 포트는 이름·단위만 돌려준다(계약 §0.1)");
        }
    }
}
