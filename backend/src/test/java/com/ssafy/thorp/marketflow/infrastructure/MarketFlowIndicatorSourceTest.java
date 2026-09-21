package com.ssafy.thorp.marketflow.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ssafy.thorp.macro.application.service.MacroSnapshotQueryService;
import com.ssafy.thorp.marketflow.application.port.MacroIndicatorSnapshotPort;
import com.ssafy.thorp.marketflow.infrastructure.example.ExampleMacroIndicatorSnapshotAdapter;
import com.ssafy.thorp.marketflow.infrastructure.example.MarketFlowExampleDataGuard;
import com.ssafy.thorp.marketflow.infrastructure.macro.MacroSnapshotIndicatorAdapter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 시장 흐름이 지표를 어디서 읽는지 고정한다 (T064).
 *
 * <p><b>2026-09-21 운영 배포가 여기서 막혔다.</b> 예시 자료 가드가 무조건 올라와 {@code prod} 기동을 거부했는데, 그 가드를 풀 방법이 008
 * 스냅샷을 읽는 구현뿐이었다. 그래서 이 테스트는 "운영이 뜬다"와 "예시 자료로는 뜨지 않는다"를 함께 고정한다 — 하나만 두면 다음에 같은 실수가 반대 방향으로 난다.
 */
@DisplayName("시장 흐름 지표 공급원 선택")
class MarketFlowIndicatorSourceTest {

    private final ApplicationContextRunner contextRunner =
            new ApplicationContextRunner()
                    .withUserConfiguration(StubMacroSnapshot.class)
                    .withBean(ObjectMapper.class, ObjectMapper::new)
                    .withUserConfiguration(
                            MacroSnapshotIndicatorAdapter.class,
                            ExampleMacroIndicatorSnapshotAdapter.class,
                            MarketFlowExampleDataGuard.class);

    @Test
    @DisplayName("설정이 없으면 008 스냅샷을 읽는다")
    void defaultsToMacroSnapshot() {
        // 기본이 예시 자료면, 설정을 빠뜨린 환경이 조용히 지어낸 값을 그린다.
        contextRunner.run(
                context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(MacroIndicatorSnapshotPort.class))
                            .isInstanceOf(MacroSnapshotIndicatorAdapter.class);
                    assertThat(context).doesNotHaveBean(ExampleMacroIndicatorSnapshotAdapter.class);
                });
    }

    @Test
    @DisplayName("운영에서 008 스냅샷으로 기동한다")
    void prodStartsWithMacroSnapshot() {
        contextRunner
                .withInitializer(context -> context.getEnvironment().setActiveProfiles("prod"))
                .withPropertyValues("thorp.marketflow.indicator-source=macro")
                .run(
                        context -> {
                            assertThat(context).hasNotFailed();
                            assertThat(context.getBean(MacroIndicatorSnapshotPort.class))
                                    .isInstanceOf(MacroSnapshotIndicatorAdapter.class);
                            // 예시 자료를 쓰지 않으므로 가드가 설 자리도 없다.
                            assertThat(context).doesNotHaveBean(MarketFlowExampleDataGuard.class);
                        });
    }

    @Test
    @DisplayName("운영에서 예시 자료를 고르면 기동에 실패한다")
    void prodWithExampleDataFailsToStart() {
        contextRunner
                .withInitializer(context -> context.getEnvironment().setActiveProfiles("prod"))
                .withPropertyValues("thorp.marketflow.indicator-source=example")
                .run(
                        context ->
                                assertThat(context)
                                        .hasFailed()
                                        .getFailure()
                                        .rootCause()
                                        .isInstanceOf(IllegalStateException.class)
                                        .hasMessageContaining("예시 자료")
                                        .hasMessageContaining("prod"));
    }

    @Test
    @DisplayName("지역 개발은 예시 자료로 기동한다")
    void localStartsWithExampleData() {
        contextRunner
                .withPropertyValues("thorp.marketflow.indicator-source=example")
                .run(
                        context -> {
                            assertThat(context).hasNotFailed();
                            assertThat(context.getBean(MacroIndicatorSnapshotPort.class))
                                    .isInstanceOf(ExampleMacroIndicatorSnapshotAdapter.class);
                        });
    }

    @Test
    @DisplayName("두 구현이 함께 올라오지 않는다")
    void onlyOneImplementationIsWired() {
        // 둘 다 올라오면 주입이 모호해져 기동이 실패하거나, 더 나쁘게는 어느 쪽이 쓰였는지
        // 모른 채 화면이 그려진다.
        contextRunner.run(
                context ->
                        assertThat(context.getBeansOfType(MacroIndicatorSnapshotPort.class))
                                .hasSize(1));
        contextRunner
                .withPropertyValues("thorp.marketflow.indicator-source=example")
                .run(
                        context ->
                                assertThat(context.getBeansOfType(MacroIndicatorSnapshotPort.class))
                                        .hasSize(1));
    }

    @Configuration
    static class StubMacroSnapshot {
        @Bean
        MacroSnapshotQueryService macroSnapshotQueryService() {
            return mock(MacroSnapshotQueryService.class);
        }
    }
}
