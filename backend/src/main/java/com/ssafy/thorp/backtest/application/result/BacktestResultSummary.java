package com.ssafy.thorp.backtest.application.result;

import com.ssafy.thorp.backtest.application.port.message.EngineOutput;
import java.time.Instant;

/**
 * 결과 행에서 요약에 필요한 것만 읽은 값. 저장소가 채운다.
 *
 * <p>시계열({@code portfolio_series})과 벤치마크는 **읽지 않는다.** 5년치면 수백 KB이고 이 값을 쓰는 {@code GET
 * /backtests/{id}}는 프론트가 폴링하는 리소스다.
 *
 * @param errorCode null = 성공한 실행
 * @param performance null = 실패로 계산 불가
 * @param execution null = 실패로 계산 불가
 * @param costs 비용 합계(FR-027). null = 실패 또는 v2 이전 결과
 * @param liquidation 청산 가정치(FR-017). null = 요청이 청산을 끄거나 실패
 * @param analytics 파생 지표(FR-027). null = 실패 또는 v2 이전 결과
 * @param exceptionCount 예외 처리 기록 건수. 본문은 {@code /exceptions}가 따로 읽는다
 * @param workerVersion 결과를 낸 워커가 보고한 자기 버전({@code versions.worker}). null = 미보고(v2 이전·결과 없는 실패)
 * @param dependencies 결과를 계산한 워커 환경의 수치 라이브러리 버전({@code versions.dependencies}). null = 미보고(T028
 *     이전·결과 없는 실패)
 */
public record BacktestResultSummary(
        String errorCode,
        String errorMessage,
        Instant startedAt,
        Instant finishedAt,
        EngineOutput.PerformanceResult performance,
        EngineOutput.ExecutionResult execution,
        EngineOutput.Costs costs,
        EngineOutput.Liquidation liquidation,
        EngineOutput.Analytics analytics,
        int exceptionCount,
        String workerVersion,
        EngineOutput.Dependencies dependencies) {}
