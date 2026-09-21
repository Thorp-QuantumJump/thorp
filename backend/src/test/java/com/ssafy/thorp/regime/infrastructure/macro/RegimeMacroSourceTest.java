package com.ssafy.thorp.regime.infrastructure.macro;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.ssafy.thorp.macro.application.repository.MacroSnapshotRepository;
import com.ssafy.thorp.regime.application.port.MacroIndicatorPort;
import com.ssafy.thorp.regime.infrastructure.macro.fixture.FixtureMacroIndicatorAdapter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 010이 등록표를 어디서 읽는지 고정한다 (T070).
 *
 * <p><b>운영은 {@code macro} 를 고르고 있었는데 그 자리에 부르면 실패하는 자리지킴이 있었다.</b> 기동은 되고 부를 때만 터지므로 배포 로그에는 아무것도
 * 남지 않았다 — 설정과 구현이 어긋난 것을 아무도 보지 못하는 모양이었다. 그래서 "어느 설정이 어느 구현을 고르는가"를 시험으로 묶어 둔다.
 */
@DisplayName("010 등록표 공급원 선택")
class RegimeMacroSourceTest {

    private final ApplicationContextRunner contextRunner =
            new ApplicationContextRunner()
                    .withUserConfiguration(StubRepository.class)
                    .withUserConfiguration(
                            MacroRegistryIndicatorAdapter.class,
                            FixtureMacroIndicatorAdapter.class);

    @Test
    @DisplayName("macro 는 008 등록표를 읽는 구현을 고른다")
    void macroSelectsTheRegistryAdapter() {
        contextRunner
                .withPropertyValues("thorp.regime.macro.source=macro")
                .run(
                        context -> {
                            assertThat(context).hasNotFailed();
                            assertThat(context.getBean(MacroIndicatorPort.class))
                                    .isInstanceOf(MacroRegistryIndicatorAdapter.class);
                        });
    }

    @Test
    @DisplayName("fixture 는 임시 대역을 고른다")
    void fixtureSelectsTheStandIn() {
        contextRunner
                .withPropertyValues("thorp.regime.macro.source=fixture")
                .run(
                        context -> {
                            assertThat(context).hasNotFailed();
                            assertThat(context.getBean(MacroIndicatorPort.class))
                                    .isInstanceOf(FixtureMacroIndicatorAdapter.class);
                        });
    }

    @Test
    @DisplayName("두 구현이 함께 올라오지 않는다")
    void onlyOneImplementationIsWired() {
        contextRunner
                .withPropertyValues("thorp.regime.macro.source=macro")
                .run(
                        context ->
                                assertThat(context.getBeansOfType(MacroIndicatorPort.class))
                                        .hasSize(1));
        contextRunner
                .withPropertyValues("thorp.regime.macro.source=fixture")
                .run(
                        context ->
                                assertThat(context.getBeansOfType(MacroIndicatorPort.class))
                                        .hasSize(1));
    }

    @Test
    @DisplayName("설정이 없으면 아무 구현도 올라오지 않는다")
    void missingSourceWiresNothing() {
        // 기본값을 두면 설정을 빠뜨린 환경이 조용히 한쪽으로 돈다. 어느 쪽이든 그 사실이
        // 드러나지 않는 것이 문제라, 고르지 않으면 고르지 않은 채로 둔다.
        contextRunner.run(
                context -> assertThat(context.getBeansOfType(MacroIndicatorPort.class)).isEmpty());
    }

    @Configuration
    static class StubRepository {
        @Bean
        MacroSnapshotRepository macroSnapshotRepository() {
            return mock(MacroSnapshotRepository.class);
        }
    }
}
