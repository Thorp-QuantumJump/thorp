package com.ssafy.thorp.backtest.application.result;

import com.ssafy.thorp.backtest.application.port.message.EngineOutput;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * {@code GET /backtests/{backtestId}/timeseries}의 유스케이스 출력. 차트용 거래일별 시계열.
 *
 * <p>{@code cash}·{@code positionValue}·{@code dailyReturn}·{@code positions}는 담지 않는다(설계
 * TimeseriesResponse 표에 없다). 보유 구성은 {@code /positions}가 따로 준다.
 *
 * @param partial 취소·한도로 끊긴 부분 결과인가
 */
public record BacktestTimeseriesResult(String backtestId, boolean partial, List<Point> points) {

    /**
     * @param benchmarkReturn null = 벤치마크 미지정이거나 그 거래일 값이 없음. 응답에서 키를 생략한다
     * @param cashFlows 분배금 등 그날 현금 흐름(FR-013). null = 없음. 응답에서 키를 생략한다
     */
    public record Point(
            LocalDate date,
            BigDecimal equity,
            BigDecimal cumulativeReturn,
            BigDecimal drawdown,
            BigDecimal benchmarkReturn,
            List<EngineOutput.CashFlow> cashFlows) {}
}
