package com.ssafy.thorp.paramsearch.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.ssafy.thorp.paramsearch.application.port.SearchLimits;
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
    @DisplayName("플래그 값이 비어 있으면 꺼진 것으로 보고 컨텍스트는 정상이다")
    void blankFlagIsTreatedAsDisabled() {
        // 공통 설정이 M3에서 true가 됐으므로 "키가 없는" 상태는 yml로 재현할 수 없다. 지켜야 하는
        // 성질은 그대로다 — true가 아닌 값은 전부 꺼짐이고, 그때 한도가 없어도 기동은 멀쩡하다.
        contextRunner
                .withPropertyValues("spring.profiles.active=none", "thorp.param-search.enabled=")
                .run(
                        context -> {
                            assertThat(context).hasNotFailed();
                            assertThat(context).doesNotHaveBean(ParamSearchProperties.class);
                        });
    }

    @Test
    @DisplayName("공통 설정은 M3에서 플래그를 켰고 네 한도가 모두 채워져 있다")
    void sharedConfigEnablesParamSearch() {
        contextRunner
                .withPropertyValues("spring.profiles.active=none")
                .run(
                        context -> {
                            assertThat(
                                            context.getEnvironment()
                                                    .getProperty("thorp.param-search.enabled"))
                                    .isEqualTo("true");
                            // 켜 둔 이상 필수 세 값이 반드시 있어야 한다. 하나라도 비면 기동이 실패한다.
                            // 실행시간 상한은 비워 두는 것이 지금의 설정이다 — 묶음 크기를 막는 것은
                            // 후보 수 상한 하나뿐이므로 그 값이 사라지면 여기서 걸린다.
                            assertThat(
                                            context.getEnvironment()
                                                    .getProperty(
                                                            "thorp.param-search.max-candidates"))
                                    .isEqualTo("100");
                            assertThat(
                                            context.getEnvironment()
                                                    .getProperty(
                                                            "thorp.param-search.time-limit-seconds"))
                                    .isNullOrEmpty();
                            // 발행 창은 서버의 벌크 워커 수와 같다. 워커를 늘리면 이 값도 같이 올린다.
                            assertThat(
                                            context.getEnvironment()
                                                    .getProperty(
                                                            "thorp.param-search.dispatch-window"))
                                    .isEqualTo("1");
                            assertThat(
                                            context.getEnvironment()
                                                    .getProperty(
                                                            "thorp.param-search.consecutive-failure-limit"))
                                    .isEqualTo("5");
                            assertThat(context).hasNotFailed();
                            assertThat(context).hasSingleBean(ParamSearchProperties.class);
                            // 유스케이스가 쓰는 모양으로도 옮겨진다(application 계층은 설정 클래스를 모른다).
                            assertThat(context).hasSingleBean(SearchLimits.class);
                            SearchLimits limits = context.getBean(SearchLimits.class);
                            assertThat(limits.hasTimeLimit())
                                    .as("실행시간 상한은 두지 않는다 — 묶음은 후보를 다 돌고 끝난다")
                                    .isFalse();
                            // 없는 한도는 임시값 목록에도 없다. 있으면 화면이 없는 제한을 말한다.
                            assertThat(limits.provisional())
                                    .containsExactlyInAnyOrder(
                                            "maxCandidates",
                                            "dispatchWindow",
                                            "consecutiveFailureLimit");
                        });
    }

    @Test
    @DisplayName("local 프로필은 필수 세 값이 있어 플래그가 켜지고 바인딩된다")
    void localProfileEnablesWithAllLimits() {
        contextRunner
                .withPropertyValues("spring.profiles.active=local")
                .run(
                        context -> {
                            assertThat(context).hasNotFailed();
                            assertThat(context).hasSingleBean(ParamSearchProperties.class);
                            ParamSearchProperties properties =
                                    context.getBean(ParamSearchProperties.class);
                            // 로컬도 공통 설정과 같은 후보 수·발행 창으로 돈다. 상한 초과
                            // 거부(FR-005b)는 max-candidates 를 낮춘 ParamSearchRejectionIT 가 맡는다.
                            assertThat(properties.getMaxCandidates()).isEqualTo(100);
                            assertThat(properties.getDispatchWindow()).isEqualTo(1);
                            assertThat(properties.getTimeLimitSeconds())
                                    .as("로컬도 실행시간 상한을 두지 않는다")
                                    .isNull();
                            // 연속 실패 한도만 로컬이 낮다 — 실패를 빨리 눈으로 보려는 개발 편의값이다.
                            assertThat(properties.getConsecutiveFailureLimit()).isEqualTo(3);
                        });
    }

    @Test
    @DisplayName("켠 채로 후보 수 상한이 비어 있으면 기동을 실패시킨다 — 값을 추측하지 않는다")
    void enabledWithoutLimitsFailsFast() {
        contextRunner
                .withPropertyValues(
                        "spring.profiles.active=none",
                        "thorp.param-search.enabled=true",
                        // 값을 지운 채 플래그를 켜는 실수를 재현한다. 실행시간 상한은 비워도 되는 값이라
                        // 이 검사의 대상이 아니다 — 묶음 크기를 막는 후보 수 상한으로 본다.
                        "thorp.param-search.max-candidates=")
                .run(
                        context -> {
                            assertThat(context).hasFailed();
                            // 예외 타입을 고정하지 않는다. 한도를 쓰는 빈(searchLimits)이 생긴 뒤로는
                            // 바인딩 실패가 그 빈의 생성 실패로 한 겹 감싸여 올라온다 — 확인할 것은
                            // 포장지가 아니라 "기동이 멈췄고 원인이 바인딩 실패"라는 사실이다.
                            assertThat(context.getStartupFailure())
                                    .hasStackTraceContaining(
                                            ConfigurationPropertiesBindException.class
                                                    .getSimpleName());
                            // 어느 값이 빠졌는지가 오류에 드러나야 설정으로 고칠 수 있다.
                            // 필드 이름은 최상위 메시지가 아니라 원인(BindValidationException)에 담긴다.
                            assertThat(context.getStartupFailure())
                                    .hasStackTraceContaining("maxCandidates");
                        });
    }

    @Test
    @DisplayName("실행시간 상한을 직접 주면 그 값으로 바인딩되고 임시값 목록에 다시 들어간다")
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
                            assertThat(properties.getTimeLimitSeconds()).isEqualTo(1800L);
                            // 상한을 되살리면 그것도 확정 전 임시값이다(FR-005a).
                            assertThat(properties.provisionalNames())
                                    .containsExactly(
                                            "maxCandidates",
                                            "dispatchWindow",
                                            "timeLimitSeconds",
                                            "consecutiveFailureLimit");
                        });
    }
}
