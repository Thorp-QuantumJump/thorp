package com.ssafy.thorp.regimelink.infrastructure.config;

import com.ssafy.thorp.regimelink.application.port.JobSizeLimits;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 설정 값을 application 이 읽을 불변 값으로 넘긴다 ({@code BacktestLimitConfig}와 같은 두 클래스 분리).
 *
 * <p>가변 프로퍼티 POJO 는 infrastructure 가 들고, application 은 {@link JobSizeLimits} 만 본다 — 유스케이스가 설정 객체의
 * setter 를 보면 실행 중에 상한이 바뀔 수 있는 것처럼 읽힌다.
 */
@Configuration
@EnableConfigurationProperties(RegimeLinkProperties.class)
public class RegimeLinkConfig {

    @Bean
    public JobSizeLimits regimeLinkJobSizeLimits(RegimeLinkProperties properties) {
        return new JobSizeLimits(properties.getJobMaxRows(), properties.getJobMaxInstruments());
    }
}
