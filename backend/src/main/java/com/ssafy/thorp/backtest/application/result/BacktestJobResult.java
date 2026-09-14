package com.ssafy.thorp.backtest.application.result;

import com.ssafy.thorp.backtest.domain.model.BacktestRequest;
import com.ssafy.thorp.backtest.domain.model.BacktestRun;
import com.ssafy.thorp.backtest.domain.model.ResultSource;
import com.ssafy.thorp.backtest.domain.model.RunStatus;
import java.time.LocalDate;
import java.util.Map;

/**
 * 접수 결과(spec 004 contracts/rest-api.md, 202 본문).
 *
 * <p>식별자는 <b>요청</b>의 것이고 상태·실효 기간·버전은 <b>실행</b>의 것이다. 여러 요청이 한 실행을 가리키므로(R-02) 두 출처를 섞어 담는다.
 *
 * @param resultSource NEW 새 계산 / JOINED 진행 중 실행에 합류 / REUSED 완료 결과 재사용
 * @param effectiveStart 워밍업 이후 실제 평가 시작일. 요청 시작일보다 뒤다
 * @param retryOf 재시도라면 원본 요청의 backtestId. 접수(T005) 경로에서는 null 이고 응답에도 싣지 않는다
 */
public record BacktestJobResult(
        String backtestId,
        RunStatus status,
        ResultSource resultSource,
        LocalDate effectiveStart,
        LocalDate effectiveEnd,
        Map<String, Object> versions,
        String retryOf) {

    /** 재시도 응답용. 접수 결과 자체는 원본을 알지 못하므로 재시도 유스케이스가 붙인다. */
    public BacktestJobResult withRetryOf(String originalBacktestId) {
        return new BacktestJobResult(
                backtestId,
                status,
                resultSource,
                effectiveStart,
                effectiveEnd,
                versions,
                originalBacktestId);
    }

    public static BacktestJobResult of(BacktestRequest request, BacktestRun run) {
        return new BacktestJobResult(
                request.getBacktestId().value(),
                run.getStatus(),
                request.getResultSource(),
                run.getPlan().effectiveStart(),
                run.getPlan().effectiveEnd(),
                run.getPlan().versions(),
                null);
    }
}
