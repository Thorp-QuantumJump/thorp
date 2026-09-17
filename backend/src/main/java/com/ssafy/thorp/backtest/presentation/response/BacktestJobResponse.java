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
 *
 * <p>{@code publicToken}은 결과 주소 {@code /results/{publicToken}}의 열쇠다(spec 005 FR-019). 로그인하지 않은 요청은
 * 이 값으로만 결과를 볼 수 있다 — {@code /backtests/{backtestId}}는 소유자 경로라 401 이다.
 */
public record BacktestJobResponse(
        String backtestId,
        RunStatus status,
        ResultSource resultSource,
        LocalDate effectiveStart,
        LocalDate effectiveEnd,
        Map<String, Object> versions,
        String publicToken) {

    public static BacktestJobResponse from(BacktestJobResult result) {
        return new BacktestJobResponse(
                result.backtestId(),
                result.status(),
                result.resultSource(),
                result.effectiveStart(),
                result.effectiveEnd(),
                result.versions(),
                result.publicToken());
    }
}
