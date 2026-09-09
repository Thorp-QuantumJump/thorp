package com.ssafy.thorp.backtest.application.result;

import com.ssafy.thorp.backtest.application.port.message.EngineOutput;
import java.util.List;

/**
 * {@code GET /backtests/{backtestId}/trades}의 유스케이스 출력.
 *
 * <p>{@code content}는 계약 DTO 그대로다 — {@code entryTime}(Instant)이며, 응답의 {@code entryDate}(거래소 현지
 * 거래일)로 바꾸는 것은 응답 매핑의 일이다. 결과 저장 포트({@code SaveBacktestResultCommand})가 {@code EngineOutput}을 그대로
 * 받는 것과 같은 규칙이다.
 */
public record BacktestTradePageResult(
        String backtestId,
        int page,
        int size,
        long totalElements,
        int totalPages,
        List<EngineOutput.TradeResult> content) {}
