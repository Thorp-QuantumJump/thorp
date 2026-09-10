package com.ssafy.thorp.paramsearch.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.ConfigurationPropertiesBindException;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * 자동 탐색 기능 플래그와 한도 필수화 검증.
 *
 * <p>여기서 지키려는 것은 두 가지가 동시에 성립한다는 것이다 — (1) 한도 값을 모르는 환경은 애플리케이션 전체가 정상 기동하고, (2) 자동 탐색을 켜면 네 값이 반드시
 * 있어야 한다. 이 둘이 같이 성립해야 미확정 수치를 코드 기본값으로 박지 않으면서(constitution "AI 작업 방식") 배포도 깨지지 않는다.
 *
 * <p>컨테이너 없이 도는 테스트다. {@code ApplicationContextRunner}가 실제 yml을 읽되 DB·브로커를 띄우지 않는다.
 */
class ParamSearchConfigTest {

    private final ApplicationContextRunner contextRunner =
            new ApplicationContextRunner()
                    .withInitializer(new ConfigDataApplicationContextInitializer())
                    .withUserConfiguration(ParamSearchConfig.class);

    @Test
    @DisplayName("플래그가 꺼져 있으면 설정이 로드되지 않고 컨텍스트는 정상이다")
    void disabledFlagKeepsContextHealthy() {
        contextRunner
                .withPropertyValues("thorp.param-search.enabled=false")
                .run(
                        context -> {
                            assertThat(context).hasNotFailed();
                            assertThat(context).doesNotHaveBean(ParamSearchProperties.class);
                        });
    }

    @Test
    @DisplayName("플래그 키가 아예 없어도 꺼진 것으로 보고 컨텍스트는 정상이다")
    void missingFlagIsTreatedAsDisabled() {
        contextRunner
                .withPropertyValues("spring.profiles.active=none")
                .run(
                        context -> {
                            assertThat(context).hasNotFailed();
                            assertThat(context).doesNotHaveBean(ParamSearchProperties.class);
                        });
    }

    @Test
    @DisplayName("공통 설정만 쓰는 환경(local 프로필 아님)은 플래그가 꺼져 있어 기동한다")
    void sharedConfigDisablesParamSearch() {
        contextRunner
                .withPropertyValues("spring.profiles.active=none")
                .run(
                        context -> {
                            assertThat(
                                            context.getEnvironment()
                                                    .getProperty("thorp.param-search.enabled"))
                                    .isEqualTo("false");
                            // 공통 설정은 확정된 임시값 셋을 갖고, 미측정인 실행시간 상한만 비어 있다.
                            assertThat(
                                            context.getEnvironment()
                                                    .getProperty(
                                                            "thorp.param-search.max-candidates"))
                                    .isEqualTo("1000");
                            assertThat(
                                            context.getEnvironment()
                                                    .getProperty(
                                                            "thorp.param-search.dispatch-window"))
                                    .isEqualTo("4");
                            assertThat(
                                            context.getEnvironment()
                                                    .getProperty(
                                                            "thorp.param-search.consecutive-failure-limit"))
                                    .isEqualTo("5");
                            assertThat(
                                            context.getEnvironment()
                                                    .getProperty(
                                                            "thorp.param-search.time-limit-seconds"))
                                    .isNullOrEmpty();
                        });
    }

    @Test
    @DisplayName("local 프로필은 네 값이 모두 있어 플래그가 켜지고 바인딩된다")
    void localProfileEnablesWithAllLimits() {
        contextRunner
                .withPropertyValues("spring.profiles.active=local")
                .run(
                        context -> {
                            assertThat(context).hasNotFailed();
                            assertThat(context).hasSingleBean(ParamSearchProperties.class);
                            ParamSearchProperties properties =
                                    context.getBean(ParamSearchProperties.class);
                            assertThat(properties.getMaxCandidates()).isEqualTo(24);
                            assertThat(properties.getDispatchWindow()).isEqualTo(4);
                            assertThat(properties.getTimeLimitSeconds()).isEqualTo(300L);
                            assertThat(properties.getConsecutiveFailureLimit()).isEqualTo(3);
                        });
    }

    @Test
    @DisplayName("켠 채로 실행시간 상한이 비어 있으면 기동을 실패시킨다 — 값을 추측하지 않는다")
    void enabledWithoutLimitsFailsFast() {
        contextRunner
                .withPropertyValues(
                        "spring.profiles.active=none", "thorp.param-search.enabled=true")
                .run(
                        context -> {
                            assertThat(context).hasFailed();
                            assertThat(context)
                                    .getFailure()
                                    .isInstanceOf(ConfigurationPropertiesBindException.class);
                            // 어느 값이 빠졌는지가 오류에 드러나야 설정으로 고칠 수 있다.
                            // 필드 이름은 최상위 메시지가 아니라 원인(BindValidationException)에 담긴다.
                            // 공통 설정에서 아직 비어 있는 값은 실행시간 상한 하나다(PD-05 잔여).
                            assertThat(context.getStartupFailure())
                                    .hasStackTraceContaining("timeLimitSeconds");
                        });
    }

    @Test
    @DisplayName("네 값을 직접 주면 켠 상태로 바인딩된다 — 확정 후 prod 설정이 이 모양이다")
    void enabledWithExplicitLimitsBinds() {
        contextRunner
                .withPropertyValues(
                        "spring.profiles.active=none",
                        "thorp.param-search.enabled=true",
                        "thorp.param-search.max-candidates=1000",
                        "thorp.param-search.dispatch-window=8",
                        "thorp.param-search.time-limit-seconds=1800",
                        "thorp.param-search.consecutive-failure-limit=5")
                .run(
                        context -> {
                            assertThat(context).hasNotFailed();
                            ParamSearchProperties properties =
                                    context.getBean(ParamSearchProperties.class);
                            assertThat(properties.getDispatchWindow()).isEqualTo(8);
                            // 확정 전까지 네 항목 모두 임시값으로 표시된다(FR-005a).
                            assertThat(properties.provisionalNames())
                                    .containsExactly(
                                            "maxCandidates",
                                            "dispatchWindow",
                                            "timeLimitSeconds",
                                            "consecutiveFailureLimit");
                        });
    }
}
