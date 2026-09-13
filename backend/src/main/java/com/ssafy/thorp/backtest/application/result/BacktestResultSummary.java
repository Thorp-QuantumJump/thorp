package com.ssafy.thorp.backtest.application.result;

import com.ssafy.thorp.backtest.application.port.message.EngineOutput;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * 결과 행에서 요약에 필요한 것만 읽은 값. 저장소가 채운다.
 *
 * <p>시계열({@code portfolio_series})과 벤치마크 전체는 **읽지 않는다.** 5년치면 수백 KB이고 이 값을 쓰는 {@code GET
 * /backtests/{id}}는 프론트가 폴링하는 리소스다. 벤치마크는 스칼라 두 개만 뽑아 온다.
 *
 * @param errorCode null = 성공한 실행
 * @param performance null = 실패로 계산 불가
 * @param execution null = 실패로 계산 불가
 * @param benchmarkInstrumentId null = 결과에 벤치마크가 없음. **요청에 지정했더라도** 워커가 계산하지 않았으면 null이다
 * @param benchmarkTotalReturn null = 위와 같음
 * @param costs 비용 합계(FR-027). null = 실패 또는 v2 이전 결과
 * @param liquidation 청산 가정치(FR-017). null = 요청이 청산을 끄거나 실패
 * @param analytics 파생 지표(FR-027). null = 실패 또는 v2 이전 결과
 * @param exceptionCount 예외 처리 기록 건수. 본문은 {@code /exceptions}가 따로 읽는다
 */
public record BacktestResultSummary(
        String errorCode,
        String errorMessage,
        Instant startedAt,
        Instant finishedAt,
        EngineOutput.PerformanceResult performance,
        EngineOutput.ExecutionResult execution,
        String benchmarkInstrumentId,
        BigDecimal benchmarkTotalReturn,
        EngineOutput.Costs costs,
        EngineOutput.Liquidation liquidation,
        EngineOutput.Analytics analytics,
        int exceptionCount) {

    /** 벤치마크 스칼라 둘이 모두 있을 때만 벤치마크가 산출된 것으로 본다. */
    public boolean hasBenchmark() {
        return benchmarkInstrumentId != null && benchmarkTotalReturn != null;
    }
}
