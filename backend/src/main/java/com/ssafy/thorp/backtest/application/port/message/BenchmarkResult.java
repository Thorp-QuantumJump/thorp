package com.ssafy.thorp.backtest.application.port.message;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 벤치마크 Buy &amp; Hold 결과. 실행 Consumer(워커)가 계산해 FINISHED payload에 합친다. 엔진 결과({@link EngineOutput})의
 * 일부가 아니다.
 */
public record BenchmarkResult(
        String instrumentId, BigDecimal totalReturn, List<BenchmarkPoint> timeseries) {

    public record BenchmarkPoint(LocalDate date, BigDecimal equity, BigDecimal cumulativeReturn) {}
}
