package com.ssafy.thorp.common.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * {@code @Scheduled} 활성화.
 *
 * <p>현재 사용처는 발행 재시도({@code JobRepublisher}, spec 004 R-18)뿐이다. 개별 스케줄러는 각자 프로퍼티로 끌 수 있어야 한다 — 여기서
 * 전역으로 켜 두고, 테스트는 해당 스케줄러만 끈 뒤 메서드를 직접 불러 검증한다.
 */
@Configuration
@EnableScheduling
public class SchedulingConfig {}
