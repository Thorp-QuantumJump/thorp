package com.ssafy.thorp.myrecord.infrastructure.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 내 기록 설정 등록(spec 006 T002).
 *
 * <p>여기서 빈으로 만들어야 기동 시점에 {@code @Validated} 검사가 돌아 값 누락이 드러난다. 상한을 실제로 재는 것은 탐색 접수 앞의 {@code
 * MyRecordLimitPolicy}(T033)이고, 그때 application 계층이 쓸 불변 값도 함께 둔다 — {@code BacktestLimitConfig}가
 * {@code ExecutionLimits}를 두는 것과 같은 방식이다.
 */
@Configuration
@EnableConfigurationProperties(MyRecordProperties.class)
public class MyRecordConfig {}
