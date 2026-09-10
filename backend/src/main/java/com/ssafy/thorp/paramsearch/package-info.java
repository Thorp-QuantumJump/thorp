/**
 * 설정 조합 자동 비교(자동 파라미터 대량 탐색). spec 007 소유 모듈이다.
 *
 * <p><b>계층 규약</b>(ADR-0001, {@code backtest} 모듈과 같은 배치):
 *
 * <ul>
 *   <li>{@code presentation} → {@code application} → {@code domain}
 *   <li>{@code infrastructure} → {@code application}, {@code domain}
 *   <li>{@code domain}은 어디에도 의존하지 않는다 — 프레임워크(Spring·JPA·Jackson)도 포함이다. {@code ArchitectureTest}가
 *       패키지 이름 패턴으로 이 규칙을 검사하므로 별도 등록 없이 적용된다
 * </ul>
 *
 * <p><b>이 모듈이 {@code backtest} 안에 있지 않은 이유</b>: 007은 004(백테스트 실행)를 소비하는 상위 유스케이스라 의존 방향이 {@code
 * paramsearch → backtest} 한쪽이어야 한다. 같은 패키지에 두면 004의 단일 실행 경로가 묶음 개념을 알게 되어 방향이 섞인다. 이름을 {@code
 * search}로 하지 않은 것은 010 경제환경 검색이 같은 낱말을 쓰기 때문이다.
 *
 * <p><b>{@code backtest} 모듈을 향한 참조는 단방향이다.</b> {@code search_candidate.backtest_job_id}가 {@code
 * backtest_job}을 가리키고, {@code backtest}는 {@code paramsearch}를 알지 못한다.
 */
package com.ssafy.thorp.paramsearch;
