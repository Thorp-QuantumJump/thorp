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

    /**
     * @param peakRssMb 이 실행 동안 표본(STARTED·PROGRESS·FINISHED 시점)으로 잰 상주 메모리의 최댓값(MB). 프로세스 누적 최고값이
     *     아니다 — 워커가 여러 실행을 순차 처리하므로 누적값이면 큰 실행 뒤의 작은 실행이 모두 그 값으로 기록된다. 못 재는 플랫폼은 생략
     * @param queue 이 실행을 <b>끝낸</b> 워커가 집었던 큐. 중복 실행(R-18)에서는 마지막 STARTED 의 워커와 결과를 낸 워커가 다를 수 있어 종결
     *     이벤트가 직접 싣는다(T012). 선택
     * @param role 끝낸 워커의 역할({@code single}/{@code flex}). 선택
     */
    public record ResourceUsage(
            BigDecimal peakRssMb,
            BigDecimal cpuSeconds,
            BigDecimal wallSeconds,
            String queue,
            String role) {}
}
