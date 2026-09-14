package com.ssafy.thorp.backtest.application.result;

import java.math.BigDecimal;

/**
 * 실행 하나의 핵심 숫자 셋(spec 004 FR-018).
 *
 * <p>{@link BacktestResultSummary}와 겹치지만 따로 두는 이유는 <b>읽는 양</b>이다. 요약은 시계열·비중·예외까지 딸려 오는 값이라 실행 하나를
 * 볼 때 쓰는 것이고, 이 record는 <b>수백 건을 한 번에</b> 훑는 경로(자동 탐색 후보 목록·선정 계산)가 쓴다. 그 경로가 요약을 쓰면 후보 수만큼의 JSON이
 * 통째로 메모리에 올라온다(backend CLAUDE.md "읽지 않을 컬럼은 select에 넣지 않는다").
 *
 * <p><b>산출되지 않은 값은 {@code null}이고 0이 아니다.</b> 0으로 채우면 "손실 없음"·"비용 없음"으로 읽혀 실제 0과 구분되지 않는다.
 */
public record ResultPerformance(
        BigDecimal netReturn, BigDecimal maxDrawdown, BigDecimal tradingCost) {}
