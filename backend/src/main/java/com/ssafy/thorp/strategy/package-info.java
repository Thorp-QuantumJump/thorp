/**
 * 전략 선택·상세 설정. spec 003 소유 모듈이다.
 *
 * <p><b>등록표의 소유자는 003이고 {@code paramsearch}는 포트로 읽는다.</b> 전략·파라미터 등록표({@code
 * registry/parameter-registry.yml})와 그것을 읽는 {@code ParameterRegistryPort}·값 타입·{@code
 * RegistryUnavailableException}이 이 모듈에 있다. 007(설정 조합 자동 비교)은 그 포트로만 등록표에 닿으며, 의존 방향은 {@code
 * paramsearch → strategy} 하나다 — {@code ArchitectureTest} 규칙 8이 반대 방향을 빌드 실패로 막는다.
 *
 * <p><b>계층 규약</b>(ADR-0001, {@code backtest}·{@code paramsearch} 모듈과 같은 배치):
 *
 * <ul>
 *   <li>{@code presentation} → {@code application} → {@code domain}
 *   <li>{@code infrastructure} → {@code application}, {@code domain}
 *   <li>{@code domain}은 어디에도 의존하지 않는다 — 프레임워크(Spring·JPA·Jackson)도 포함이다
 * </ul>
 *
 * <p><b>{@code domain/model}을 두지 않는다.</b> 이 모듈이 다루는 값은 등록표가 준 등록 정보뿐이고 그 자체로 생명주기를 가진 도메인 객체가 아니다.
 * 값 타입은 {@code application/port}에 둔다 — 포트가 주고받는 형태이기 때문이다.
 */
package com.ssafy.thorp.strategy;
