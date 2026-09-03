package com.thorp.api.common.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

/**
 * JPA Auditing 활성화.
 *
 * <p>Application 클래스에 {@code @EnableJpaAuditing}을 붙이면 {@code @WebMvcTest} 같은 슬라이스 테스트에서 JPA 빈을 찾지
 * 못해 컨텍스트가 깨진다. 별도 설정 클래스로 분리해 슬라이스 테스트에서는 자동 제외되게 한다.
 */
@Configuration
@EnableJpaAuditing
public class JpaAuditingConfig {}
