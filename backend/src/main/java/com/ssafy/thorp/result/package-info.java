/**
 * 결과 공개 열람 경계(spec 005). 004의 결과를 <b>공개 토큰</b>으로 읽는다.
 *
 * <p>값을 계산하지 않고 필드를 고른다. 004 응답에서 요청자·{@code backtestId}·실행 식별자·워커 자기 보고를
 * 버리고(contracts/result-api.md "공개 응답에서 버리는 필드"), 토큰의 형식·존재·만료를 판정한 뒤 004의 요청 기준 읽기({@code
 * BacktestResultQueryService})에 넘긴다.
 *
 * <p><b>의존 방향은 {@code result → backtest} 하나다</b>(ArchUnit 규칙 9). {@code backtest}는 이 패키지를 모른다 — 토큰
 * 값 타입과 발급은 004 접수에 있고, 유효 시간은 설정 키로만 읽는다.
 *
 * <p>계층 규약은 ADR-0001을 따르되 {@code domain}·{@code application/repository}는 두지 않는다. 값 타입과 저장은 {@code
 * backtest}가 소유한다. 하위 패키지는 {@code presentation/{controller,response}}, {@code
 * application/{service,result}}, {@code infrastructure/config}다.
 */
package com.ssafy.thorp.result;
