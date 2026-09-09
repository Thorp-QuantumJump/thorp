package com.ssafy.thorp.backtest.application.result;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * {@code GET /backtests/{backtestId}/timeseries}의 유스케이스 출력. 차트용 거래일별 시계열.
 *
 * <p>{@code cash}·{@code positionValue}·{@code dailyReturn}·{@code positions}는 담지 않는다(설계
 * TimeseriesResponse 표에 없다). 보유 구성은 {@code /positions}가 따로 준다.
 */
public record BacktestTimeseriesResult(String backtestId, List<Point> points) {

    /**
     * @param benchmarkReturn null = 벤치마크 미지정이거나 그 거래일 값이 없음. 응답에서 키를 생략한다
     */
    public record Point(
            LocalDate date,
            BigDecimal equity,
            BigDecimal cumulativeReturn,
            BigDecimal drawdown,
            BigDecimal benchmarkReturn) {}
}
