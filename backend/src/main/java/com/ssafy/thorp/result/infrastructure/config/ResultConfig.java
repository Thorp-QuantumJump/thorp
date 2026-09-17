package com.ssafy.thorp.result.infrastructure.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 결과 공개 열람 설정을 등록한다({@code BacktestLimitConfig}와 같은 방식).
 *
 * <p>등록만 해도 {@link ResultProperties}의 {@code @NotNull} 검증이 기동 시점에 돈다. 값을 쓰는 유스케이스(공개 열람·만료 정리)는
 * spec 005 B02 가 붙인다.
 */
@Configuration
@EnableConfigurationProperties(ResultProperties.class)
public class ResultConfig {}
