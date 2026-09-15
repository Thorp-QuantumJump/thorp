package com.ssafy.thorp.backtest.infrastructure.config;

import com.ssafy.thorp.backtest.application.port.ExecutionLimits;
import java.time.Duration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 실행 한도 설정을 등록하고, application 계층이 쓰는 {@link ExecutionLimits} 로 옮긴다.
 *
 * <p>서비스가 {@link BacktestLimitProperties} 를 직접 주입받지 않는 이유: 그 클래스는 infrastructure 소유이고 setter 가 열린
 * 가변 객체다. 유스케이스는 불변 값만 보면 된다.
 */
@Configuration
@EnableConfigurationProperties(BacktestLimitProperties.class)
public class BacktestLimitConfig {

    @Bean
    public ExecutionLimits executionLimits(BacktestLimitProperties properties) {
        return new ExecutionLimits(
                properties.getMaxQueuedPerUser(),
                properties.getMaxQueuedTotal(),
                Duration.ofSeconds(properties.getMaxRunSeconds()),
                Duration.ofSeconds(properties.getStaleRunSeconds()),
                Duration.ofSeconds(properties.getCancelGraceSeconds()),
                properties.isProvisional());
    }
}
