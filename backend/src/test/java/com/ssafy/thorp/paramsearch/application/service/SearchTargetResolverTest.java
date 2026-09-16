package com.ssafy.thorp.paramsearch.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ssafy.thorp.common.exception.ErrorCode;
import com.ssafy.thorp.paramsearch.application.port.StrategyExecutionSupportPort;
import com.ssafy.thorp.paramsearch.application.service.SearchTargetResolver.SearchTargets;
import com.ssafy.thorp.paramsearch.domain.model.StopReason;
import com.ssafy.thorp.paramsearch.registry.RegistryFixtures;
import com.ssafy.thorp.strategy.application.exception.RegistryUnavailableException;
import com.ssafy.thorp.strategy.application.port.ParameterRegistryPort;
import com.ssafy.thorp.strategy.application.port.StrategyRegistration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 탐색 대상 판정(T036c·T131).
 *
 * <p>대상은 '003 활성 전략' ∩ '004 지원 전략'이다. 두 사실을 각각의 소유자에게 묻고 007은 어느 쪽도 대신 선언하지 않는다(FR-026,
 * SC-010·SC-012).
 */
class SearchTargetResolverTest {

    private final ParameterRegistryPort registry =
            RegistryFixtures.registry("FIXTURE-1")
                    .strategy("STRATEGY_A", RegistryFixtures.axisOfSize("x", 3))
                    .strategy("STRATEGY_B", RegistryFixtures.axisOfSize("y", 2))
                    .build();

    @Test
    @DisplayName("등록되고 실행도 지원하는 전략만 탐색 대상이 된다")
    void targetsAreTheIntersection() {
        SearchTargets targets =
                resolver(registry, "STRATEGY_A", "STRATEGY_B")
                        .resolve(List.of("STRATEGY_A", "STRATEGY_B"));

        assertThat(targets.isRejected()).isFalse();
        assertThat(targets.targets())
                .extracting(StrategyRegistration::strategyId)
                .containsExactly("STRATEGY_A", "STRATEGY_B");
        assertThat(targets.registryVersion()).isEqualTo("FIXTURE-1");
    }

    @Test
    @DisplayName("004 지원 목록에 없는 전략은 탐색 대상에서 빠진다")
    void unsupportedStrategyIsRejected() {
        SearchTargets targets =
                resolver(registry, "STRATEGY_A").resolve(List.of("STRATEGY_A", "STRATEGY_B"));

        assertThat(targets.isRejected()).isTrue();
        assertThat(targets.notExecutable()).containsExactly("STRATEGY_B");
        assertThat(targets.stopReason()).isEqualTo(StopReason.STRATEGY_NOT_EXECUTABLE);
        assertThat(targets.toException().getErrorCode())
                .isEqualTo(ErrorCode.STRATEGY_NOT_EXECUTABLE);
    }

    @Test
    @DisplayName("목표 비중이 사용자에게서 오는 전략은 탐색 대상에서 빠진다")
    void strategyNeedingUserWeightsIsRejected() {
        // 자동 탐색은 목표 비중을 만들지 않는다. 그대로 발행하면 후보 전량이 접수에서 거절돼 실행 자원을 버린다.
        ParameterRegistryPort withUserWeights =
                RegistryFixtures.registry("FIXTURE-1")
                        .strategy("STRATEGY_A", RegistryFixtures.axisOfSize("x", 3))
                        .strategy("STRATEGY_B", RegistryFixtures.axisOfSize("y", 2))
                        .targetWeightSource("STRATEGY_B", "USER")
                        .build();

        SearchTargets targets =
                resolver(withUserWeights, "STRATEGY_A", "STRATEGY_B")
                        .resolve(List.of("STRATEGY_A", "STRATEGY_B"));

        assertThat(targets.isRejected()).isTrue();
        assertThat(targets.notSearchable()).containsExactly("STRATEGY_B");
        assertThat(targets.notExecutable())
                .as("004는 이 전략을 실행할 수 있다 — 두 사유를 뭉치면 사용자가 '기다리면 되는 일'로 읽는다")
                .isEmpty();
        assertThat(targets.stopReason()).isEqualTo(StopReason.STRATEGY_NOT_SEARCHABLE);
        assertThat(targets.toException().getErrorCode())
                .isEqualTo(ErrorCode.STRATEGY_NOT_SEARCHABLE);
    }

    @Test
    @DisplayName("축이 없어도 목표 비중을 계산하는 전략이면 탐색 대상이다")
    void axisCountDoesNotDecideSearchability() {
        // 예전 판정이던 "축이 있는가"로 갈랐다면 여기서 빠졌을 전략이다. 축의 유무는 설정할 항목이 있는지를
        // 말할 뿐 실행 입력을 누가 대는지를 말하지 않는다.
        ParameterRegistryPort noAxis =
                RegistryFixtures.registry("FIXTURE-1").strategy("STRATEGY_C").build();

        SearchTargets targets = resolver(noAxis, "STRATEGY_C").resolve(List.of("STRATEGY_C"));

        assertThat(targets.isRejected()).isFalse();
        assertThat(targets.targets())
                .extracting(StrategyRegistration::strategyId)
                .containsExactly("STRATEGY_C");
    }

    @Test
    @DisplayName("미등록이 탐색 불가보다 먼저 알려진다 — 사용자가 할 일의 순서와 같다")
    void unregisteredIsReportedBeforeNotSearchable() {
        ParameterRegistryPort withUserWeights =
                RegistryFixtures.registry("FIXTURE-1")
                        .strategy("STRATEGY_B", RegistryFixtures.axisOfSize("y", 2))
                        .targetWeightSource("STRATEGY_B", "USER")
                        .build();

        SearchTargets targets =
                resolver(withUserWeights, "STRATEGY_B")
                        .resolve(List.of("STRATEGY_MISSING", "STRATEGY_B"));

        assertThat(targets.notRegistered()).containsExactly("STRATEGY_MISSING");
        assertThat(targets.notSearchable()).containsExactly("STRATEGY_B");
        assertThat(targets.stopReason()).isEqualTo(StopReason.STRATEGY_NOT_REGISTERED);
    }

    @Test
    @DisplayName("지원 목록에 전략을 더하면 007 코드 변경 없이 대상이 된다")
    void addingSupportMakesItATargetWithoutCodeChange() {
        SearchTargets before = resolver(registry, "STRATEGY_A").resolve(List.of("STRATEGY_B"));
        SearchTargets after =
                resolver(registry, "STRATEGY_A", "STRATEGY_B").resolve(List.of("STRATEGY_B"));

        assertThat(before.isRejected()).isTrue();
        assertThat(after.isRejected()).isFalse();
        assertThat(after.targets())
                .extracting(StrategyRegistration::strategyId)
                .containsExactly("STRATEGY_B");
    }

    @Test
    @DisplayName("미등록과 실행 미지원을 구분해 거부한다")
    void separatesUnregisteredFromUnsupported() {
        SearchTargets unregistered =
                resolver(registry, "STRATEGY_A").resolve(List.of("STRATEGY_Z"));

        assertThat(unregistered.notRegistered()).containsExactly("STRATEGY_Z");
        assertThat(unregistered.notExecutable()).isEmpty();
        assertThat(unregistered.stopReason()).isEqualTo(StopReason.STRATEGY_NOT_REGISTERED);
        assertThat(unregistered.toException().getErrorCode())
                .isEqualTo(ErrorCode.STRATEGY_NOT_REGISTERED);
    }

    @Test
    @DisplayName("둘 다 걸리면 미등록을 먼저 알린다")
    void unregisteredIsReportedFirst() {
        SearchTargets targets =
                resolver(registry, "STRATEGY_A").resolve(List.of("STRATEGY_B", "STRATEGY_Z"));

        assertThat(targets.notRegistered()).containsExactly("STRATEGY_Z");
        assertThat(targets.notExecutable()).containsExactly("STRATEGY_B");
        assertThat(targets.stopReason()).isEqualTo(StopReason.STRATEGY_NOT_REGISTERED);
    }

    @Test
    @DisplayName("등록표 조회가 실패하면 대체값 없이 503으로 끊는다")
    void registryFailureIsNotSubstituted() {
        SearchTargetResolver resolver =
                new SearchTargetResolver(
                        RegistryFixtures.unavailable(), RegistryFixtures.supporting("STRATEGY_A"));

        assertThatThrownBy(() -> resolver.resolve(List.of("STRATEGY_A")))
                .isInstanceOf(RegistryUnavailableException.class)
                .extracting(e -> ((RegistryUnavailableException) e).getErrorCode())
                .isEqualTo(ErrorCode.REGISTRY_UNAVAILABLE);
    }

    @Test
    @DisplayName("탐색 공간은 등록표가 준 축과 후보값을 그대로 옮긴다")
    void fullSearchSpaceMirrorsTheRegistry() {
        SearchTargets targets =
                resolver(registry, "STRATEGY_A", "STRATEGY_B")
                        .resolve(List.of("STRATEGY_A", "STRATEGY_B"));

        assertThat(targets.fullSearchSpace().strategyIds())
                .containsExactly("STRATEGY_A", "STRATEGY_B");
        assertThat(targets.fullSearchSpace().valuesOf("STRATEGY_A", "x"))
                .isEqualTo(registry.find("STRATEGY_A").orElseThrow().axes().get(0).searchValues());
        // 3 + 2 = 전략별 곱의 합
        assertThat(targets.fullSearchSpace().combinationCount()).isEqualTo(5L);
    }

    @Test
    @DisplayName("전략을 하나도 고르지 않은 요청은 거부한다")
    void emptyRequestIsRejected() {
        SearchTargetResolver resolver = resolver(registry, "STRATEGY_A");

        assertThatThrownBy(() -> resolver.resolve(List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static SearchTargetResolver resolver(
            ParameterRegistryPort registry, String... supportedStrategyIds) {
        StrategyExecutionSupportPort support = RegistryFixtures.supporting(supportedStrategyIds);
        return new SearchTargetResolver(registry, support);
    }
}
