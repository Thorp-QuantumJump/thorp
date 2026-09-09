package com.ssafy.thorp.backtest.application.command;

import com.ssafy.thorp.backtest.application.port.message.TradeStatus;

/**
 * 거래 내역 조회 조건. 컨트롤러가 요청 DTO({@code TradePageQuery})를 검증·정규화해 만든다.
 *
 * <p>여기 오는 값은 이미 유효하다 — 범위 검증과 {@code instrumentId} 형식 검증, 기본값 적용이 컨트롤러에서 끝난다. 서비스는 요청 DTO를 보지
 * 않는다(계층 규칙: 서비스는 {@code command}/{@code result}만 받는다).
 *
 * @param page 0 이상
 * @param size 1~1000. 요청에서 생략하면 100
 * @param instrumentId null = 필터 없음
 * @param status null = 필터 없음
 */
public record FindBacktestTradesQuery(
        int page, int size, String instrumentId, TradeStatus status) {}
