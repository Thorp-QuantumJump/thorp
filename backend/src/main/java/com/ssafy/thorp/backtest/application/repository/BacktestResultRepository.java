package com.ssafy.thorp.backtest.application.repository;

import com.ssafy.thorp.backtest.application.command.SaveBacktestResultCommand;
import com.ssafy.thorp.backtest.application.port.message.EngineOutput;
import com.ssafy.thorp.backtest.application.result.BacktestResultSummary;
import com.ssafy.thorp.backtest.application.result.BacktestSeries;
import java.util.List;
import java.util.Optional;

/**
 * 백테스트 결과 저장소 포트. 구현은 {@code infrastructure.persistence}에 둔다.
 *
 * <p>조회 메서드가 여러 개인 이유는 **엔드포인트마다 읽어야 하는 컬럼이 다르기 때문이다.** 결과 행에는 5년치면 수백 KB인 {@code
 * portfolio_series}가 있고, 엔티티를 그대로 읽으면 그것과 벤치마크 시계열이 무조건 함께 온다({@code @JdbcTypeCode(JSON)} 기본 타입 속성은
 * 지연 로딩이 없다). 읽지 않을 컬럼은 select에 넣지 않는다.
 */
public interface BacktestResultRepository {

    /**
     * 결과 행 1개와 거래 행 N개를 저장한다. 실패한 실행도 저장한다(오류 정보만 채워지고 성과·집계는 null).
     *
     * <p>같은 Job에 결과가 이미 있으면 PK 충돌로 실패한다. 호출자가 Job 상태(최종 여부)로 먼저 걸러낸다.
     */
    void save(SaveBacktestResultCommand command);

    /**
     * 요약에 필요한 스칼라만 읽는다. {@code portfolio_series}와 벤치마크 시계열은 읽지 않는다.
     *
     * <p>빈 {@link Optional} = 결과 행이 없다. 호출자가 Job 상태와 맞춰 해석한다 — {@code QUEUED}·{@code RUNNING}이면
     * 정상이고, {@code COMPLETED}·{@code FAILED}면 저장 트랜잭션이 깨진 데이터 손상이다.
     */
    Optional<BacktestResultSummary> findSummary(Long backtestJobId);

    /**
     * 시계열 + 벤치마크. {@code /timeseries}가 날짜로 조인해 쓴다.
     *
     * <p>이 엔드포인트는 원래 시계열을 내려주는 곳이라 크게 읽는 것이 정상이다. 그래도 {@code performance}·{@code execution}·오류 컬럼은
     * 읽지 않는다.
     */
    Optional<BacktestSeries> findTimeseries(Long backtestJobId);

    /**
     * 시계열만. {@code /positions}가 역순 스캔에 쓴다.
     *
     * <p>{@link #findTimeseries}를 재사용하지 않는 이유: 벤치마크 시계열도 거래일 수만큼 크고 보유 구성 조회에는 전혀 쓰이지 않는다. 읽지 않을
     * 컬럼은 select에 넣지 않는다.
     */
    Optional<List<EngineOutput.PortfolioSnapshot>> findPortfolioSeries(Long backtestJobId);
}
