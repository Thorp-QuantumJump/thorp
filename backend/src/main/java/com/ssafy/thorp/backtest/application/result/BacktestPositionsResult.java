package com.ssafy.thorp.backtest.application.result;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * {@code GET /backtests/{backtestId}/positions?date=}의 유스케이스 출력.
 *
 * <p>스냅샷은 보유 구성이 바뀐 거래일 + 시작일 + 종료일에만 있다. 그래서 요청일 이하에서 스냅샷이 있는 가장 최근 날짜를 돌려주고 그 날짜를 {@code
 * snapshotDate}에 담는다 — 보유 종목·수량은 정확하지만 평가액은 그 날짜의 Close 기준이라 화면이 어느 시점 기준인지 표시할 수 있어야 한다.
 *
 * <p>{@code equity}·{@code cash}·{@code positionValue}는 담지 않는다(설계 예시에 없다). 총자산은 {@code
 * /timeseries}가 날짜별로 이미 준다.
 *
 * @param partial 취소·한도로 끊긴 부분 결과인가. 먼저 취소한 요청은 스냅샷 날짜 이하의 보유만 본다(R-04)
 * @param snapshotDate {@code requestedDate}보다 앞일 수 있다
 * @param positions 빈 리스트 = 그날 보유 없음이 확정. "스냅샷 없음"과 다르다
 */
public record BacktestPositionsResult(
        String backtestId,
        boolean partial,
        LocalDate requestedDate,
        LocalDate snapshotDate,
        List<Position> positions) {

    public record Position(
            String instrumentId,
            BigDecimal quantity,
            BigDecimal marketValue,
            BigDecimal weight,
            BigDecimal unrealizedPnl) {}
}
