package com.ssafy.thorp.regimelink.application.port;

import java.time.LocalDate;

/**
 * 링크가 기다리는 004 실행의 진행 상태 (contracts §3 {@code runProgress}).
 *
 * <p>004 {@code GET /backtests/{id}}가 내는 값을 <b>그대로</b> 옮긴다 — 011이 다시 해석하지 않는다. 화면은 005 비교 대상과 같은 진행
 * 표시를 쓴다.
 *
 * @param status 004 실행 상태 문자열({@code QUEUED}·{@code RUNNING}·{@code COMPLETED}·{@code
 *     FAILED}·{@code CANCELLED})
 */
public record RunProgressView(String status, Progress progress, Long queueWaitSeconds) {

    public record Progress(
            String stage,
            LocalDate lastTradingDate,
            Integer completedTradingDays,
            Integer totalTradingDays) {}

    public boolean completed() {
        return "COMPLETED".equals(status);
    }

    /** 다시 끝나지 않을 실행인가. 링크를 {@code FAILED(RUN_FAILED)}로 끝낸다. */
    public boolean deadEnd() {
        return "FAILED".equals(status) || "CANCELLED".equals(status);
    }
}
