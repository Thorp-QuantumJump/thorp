package com.ssafy.thorp.backtest.application.result;

import com.ssafy.thorp.backtest.application.port.message.EngineOutput;
import com.ssafy.thorp.backtest.application.port.message.WeightsSource;
import java.util.List;

/**
 * 결과 행의 비중·예외 블록(FR-026·FR-030). {@code /weights}·{@code /exceptions}가 쓴다.
 *
 * <p>둘을 한 조회로 읽는 이유: 리밸런싱 기록은 월 1건, 예외는 사건당 1건이라 둘 다 KB 단위고, 시계열(수백 KB)은 읽지 않는다는 점이 중요하다. 엔드포인트마다
 * 조회를 쪼개면 코드만 늘고 얻는 것이 없다.
 *
 * @param weightsSeries 비어 있을 수 있다(엔진이 채우지 않은 결과). null 은 없다
 * @param weightsSource null = 엔진이 기록하지 않았다
 * @param exceptions 비어 있을 수 있다. null 은 없다
 */
public record BacktestResultBlocks(
        List<EngineOutput.WeightsPoint> weightsSeries,
        WeightsSource weightsSource,
        List<EngineOutput.ExceptionRecord> exceptions) {}
