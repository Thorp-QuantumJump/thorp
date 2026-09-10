package com.ssafy.thorp.paramsearch.infrastructure.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 자동 탐색 모듈 설정. 지금은 {@link ParamSearchProperties} 등록만 한다.
 *
 * <p>애플리케이션 전역에 {@code @ConfigurationPropertiesScan}을 걸지 않고 모듈 안에서 등록한다. 전역 스캔은 다른 파트가 만든 설정 클래스까지
 * 함께 켜서 이 모듈의 변경이 바깥에 영향을 준다.
 */
@Configuration
@EnableConfigurationProperties(ParamSearchProperties.class)
public class ParamSearchConfig {}
