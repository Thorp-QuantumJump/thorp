package com.ssafy.thorp.paramsearch.infrastructure.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 자동 탐색 모듈 설정. 지금은 {@link ParamSearchProperties} 등록만 한다.
 *
 * <p>애플리케이션 전역에 {@code @ConfigurationPropertiesScan}을 걸지 않고 모듈 안에서 등록한다. 전역 스캔은 다른 파트가 만든 설정 클래스까지
 * 함께 켜서 이 모듈의 변경이 바깥에 영향을 준다.
 *
 * <p><b>{@code thorp.param-search.enabled=true}일 때만 로드된다.</b> {@link ParamSearchProperties}의 네 한도가
 * {@code @NotNull}인데 그 값들이 PD-05·PD-06 확정 대기라 공통 설정에서 비어 있다. 이 설정이 무조건 로드되면 값을 채우지 않은 환경에서 <b>자동
 * 탐색만 죽는 것이 아니라 애플리케이션 전체가 기동에 실패</b>한다 — 007이 아직 아무 엔드포인트도 노출하지 않는 단계에서 기존 기능까지 함께 내려가는 것은 받아들일 수
 * 없다.
 *
 * <p>키가 없으면 꺼진 것으로 본다({@code matchIfMissing} 기본값). 그래서 값을 모르는 환경은 그냥 부팅되고, 켜는 순간 네 값이 필수가 된다. 값을
 * 추측해 기본값으로 박는 것(constitution "AI 작업 방식" 위반)과 배포를 깨는 것 사이의 세 번째 선택이다.
 *
 * <p><b>다음 묶음에게</b>: {@link ParamSearchProperties}를 주입하는 빈은 이 설정과 같은 조건을 가져야 한다. 같은
 * {@code @ConditionalOnProperty}를 붙이거나 이 클래스의 {@code @Bean}으로 선언한다. 조건 없이 {@code @Service}로 두면 자동
 * 탐색이 꺼진 환경에서 그 빈이 프로퍼티를 찾지 못해 기동이 실패하고, 이 플래그를 둔 이유가 없어진다.
 */
@Configuration
@ConditionalOnProperty(prefix = "thorp.param-search", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(ParamSearchProperties.class)
public class ParamSearchConfig {}
