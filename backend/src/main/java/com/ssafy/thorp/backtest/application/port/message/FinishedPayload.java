package com.ssafy.thorp.backtest.application.port.message;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * FINISHED 이벤트 payload. {@code status}는 {@code engineOutput.run.status}와 같아야 한다(계약 검증).
 *
 * @param status COMPLETED · FAILED · CANCELLED
 * @param partial true = 취소·한도 도달로 끊긴 부분 결과(CANCELLED). COMPLETED·FAILED는 false
 * @param lastTradingDate 처리한 마지막 거래일. FAILED는 null 가능
 * @param resource 실행 프로세스 자원 사용(측정 기록용). null = 미측정
 */
public record FinishedPayload(
        RunStatus status,
        boolean partial,
        LocalDate lastTradingDate,
        EngineOutput engineOutput,
        ResourceUsage resource) {

    public record ResourceUsage(
            BigDecimal peakRssMb, BigDecimal cpuSeconds, BigDecimal wallSeconds) {}
}
