package com.ssafy.thorp.regime.infrastructure.macro;

import static org.assertj.core.api.Assertions.assertThat;

import com.ssafy.thorp.regime.application.port.FieldRegistration;
import com.ssafy.thorp.regime.application.port.IndicatorRegistration;
import com.ssafy.thorp.regime.application.port.MacroIndicatorPort;
import com.ssafy.thorp.regime.infrastructure.macro.fixture.FixtureMacroIndicatorAdapter;
import java.util.List;
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
        // 008 이 오면 그 구현체를 여기 더한다. 대역을 지우는 것은 그다음이다.
        return Stream.of(new FixtureMacroIndicatorAdapter());
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
        throw new IllegalStateException(
                "새 구현체를 더했으면 이 시험이 쓸 지표 하나를 고르는 자리도 함께 고친다: " + port.getClass().getSimpleName());
    }
}
