package com.ssafy.thorp.common.config;

import org.springframework.security.config.annotation.web.builders.HttpSecurity;

/**
 * 도메인이 공통 보안 경계({@link SecurityConfig})에 자기 설정을 더하는 자리.
 *
 * <p>{@code common}은 도메인 패키지를 참조하지 않는다(ADR-0001, ArchUnit 규칙 4). 그래서 소셜 로그인처럼 도메인 코드가 필요한 필터 설정은
 * 도메인이 이 인터페이스로 넘기고, {@code SecurityConfig}는 받은 것을 순서대로 적용만 한다.
 */
public interface HttpSecurityContributor {

    void contribute(HttpSecurity http) throws Exception;
}
