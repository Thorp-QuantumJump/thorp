package com.ssafy.thorp.backtest.presentation.response;

import com.ssafy.thorp.backtest.application.result.BacktestJobResult;
import com.ssafy.thorp.backtest.domain.model.ResultSource;
import com.ssafy.thorp.backtest.domain.model.RunStatus;
import java.time.LocalDate;
import java.util.Map;

/**
 * 접수 202 응답(spec 004 contracts/rest-api.md).
 *
 * <p>{@code resultSource}가 {@code REUSED}면 {@code status}는 이미 {@code COMPLETED}이고 결과를 바로 조회할 수 있다.
 * 프론트는 상태만 보고 폴링을 시작하지 말고 이 값을 먼저 봐야 한다.
 */
public record BacktestJobResponse(
        String backtestId,
        RunStatus status,
        ResultSource resultSource,
        LocalDate effectiveStart,
        LocalDate effectiveEnd,
        Map<String, Object> versions) {

    public static BacktestJobResponse from(BacktestJobResult result) {
        return new BacktestJobResponse(
                result.backtestId(),
                result.status(),
                result.resultSource(),
                result.effectiveStart(),
                result.effectiveEnd(),
                result.versions());
    }
}
