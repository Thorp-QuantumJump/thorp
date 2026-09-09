package com.ssafy.thorp.backtest.application.result;

import com.ssafy.thorp.backtest.application.port.message.BenchmarkResult;
import com.ssafy.thorp.backtest.application.port.message.EngineOutput;
import java.util.List;

/**
 * 결과 행의 시계열 컬럼을 파싱한 값. 저장소가 채운다.
 *
 * <p>{@code /timeseries}는 벤치마크를 날짜로 조인해야 하므로 둘 다 필요하고, {@code /positions}는 시계열 하나만 있으면 되므로 저장소에
 * 메서드가 따로 있다({@code findPortfolioSeries}). 벤치마크 시계열도 거래일 수만큼 크기 때문에 필요 없는 쪽이 읽지 않는다.
 *
 * @param benchmark null = 결과에 벤치마크가 없음
 */
public record BacktestSeries(
        List<EngineOutput.PortfolioSnapshot> portfolioSeries, BenchmarkResult benchmark) {}
