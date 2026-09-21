package com.ssafy.thorp.regimelink.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.ssafy.thorp.regimelink.application.port.JobSizeLimits;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.autoconfigure.validation.ValidationAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * 설정 누락이 기동 실패로 드러나는지 (T080).
 *
 * <p>값이 아니라 <b>장치</b>를 시험한다 — 어떤 수가 들어 있는지가 아니라, 비었을 때 조용히 기본값으로 굴러가지 않는지를 본다. 설정이 빠진 채 뜨면 감시가 돌지
 * 않거나 상한 없이 발행되는데 아무도 알아채지 못한다.
 */
@DisplayName("사례 연결 운영 설정")
class RegimeLinkPropertiesTest {

    private final ApplicationContextRunner runner =
            new ApplicationContextRunner()
                    .withConfiguration(
                            org.springframework.boot.autoconfigure.AutoConfigurations.of(
                                    ConfigurationPropertiesAutoConfiguration.class,
                                    ValidationAutoConfiguration.class))
                    .withUserConfiguration(RegimeLinkConfig.class);

    @Test
    @DisplayName("세 값이 모두 있으면 뜬다")
    void 값이_다_있으면_뜬다() {
        runner.withPropertyValues(
                        "thorp.regimelink.pending-run-sweep-interval=PT30S",
                        "thorp.regimelink.job-max-rows=65",
                        "thorp.regimelink.job-max-instruments=8")
                .run(
                        context -> {
                            assertThat(context).hasNotFailed();
                            assertThat(
                                            context.getBean(RegimeLinkProperties.class)
                                                    .getPendingRunSweepInterval())
                                    .isEqualTo(Duration.ofSeconds(30));
                            assertThat(context.getBean(JobSizeLimits.class))
                                    .isEqualTo(new JobSizeLimits(65, 8));
                        });
    }

    @Test
    @DisplayName("감시 간격이 없으면 기동에 실패한다")
    void 감시_간격_누락() {
        runner.withPropertyValues(
                        "thorp.regimelink.job-max-rows=65",
                        "thorp.regimelink.job-max-instruments=8")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    @DisplayName("작업 상한이 없으면 기동에 실패한다")
    void 상한_누락() {
        runner.withPropertyValues("thorp.regimelink.pending-run-sweep-interval=PT30S")
                .run(context -> assertThat(context).hasFailed());
    }
}
