package com.ssafy.thorp.backtest.application.repository;

import com.ssafy.thorp.backtest.application.command.FindBacktestTradesQuery;
import com.ssafy.thorp.backtest.application.command.SaveBacktestResultCommand;
import com.ssafy.thorp.backtest.application.port.message.EngineOutput;
import com.ssafy.thorp.backtest.application.result.BacktestResultBlocks;
import com.ssafy.thorp.backtest.application.result.BacktestResultSummary;
import com.ssafy.thorp.backtest.application.result.BacktestSeries;
import com.ssafy.thorp.backtest.application.result.ResultPerformance;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.data.domain.Page;

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
     * 결과 행이 있는지만 본다. JSON 컬럼을 읽지도 파싱하지도 않는다.
     *
     * <p>{@code /trades}는 {@code backtest_trade} 행만 읽어서 결과 행 유실을 스스로 알아채지 못한다 — 거래가 0건이면 빈 페이지를, 거래
     * 행만 남아 있으면 목록을 정상 응답으로 돌려준다. 그 경로를 막기 위한 존재 확인이다. {@link #findSummary}로 대신하면 쓰지도 않을 {@code
     * performance}·{@code execution} JSON을 역직렬화한다.
     */
    boolean exists(Long runId);

    /**
     * 요약에 필요한 컬럼만 읽는다. 오류·시각 스칼라와 성과·실행·비용·청산·분석·의존성 JSON이고, {@code portfolio_series}와 {@code
     * benchmark}는 읽지 않는다.
     *
     * <p>빈 {@link Optional} = 결과 행이 없다. 호출자가 Job 상태와 맞춰 해석한다 — {@code QUEUED}·{@code RUNNING}이면
     * 정상이다. 최종 상태에서는 시작 전 취소와 워커 산출물 없이 끝난 실패만 정상이고, 그 밖에는 저장 트랜잭션이 깨진 데이터 손상이다.
     */
    Optional<BacktestResultSummary> findSummary(Long runId);

    /**
     * 실행 여러 건의 핵심 숫자 셋만 한 번에 읽는다. {@code performance} JSONB에서 세 값만 꺼내고 시계열·비중·예외는 읽지 않는다.
     *
     * <p>{@link #findSummary}를 반복 호출하지 않는 이유가 둘이다 — 왕복이 실행 수만큼 늘고, 호출마다 쓰지도 않을 {@code
     * execution}·{@code costs}·{@code analytics} JSON을 역직렬화한다. 자동 탐색은 묶음 하나가 후보 수백 건이라 그 차이가 그대로
     * 드러난다.
     *
     * @return 결과 행이 있는 실행만 담는다. 없는 id는 키 자체가 없다
     */
    Map<Long, ResultPerformance> findPerformanceByRunIds(Collection<Long> runIds);

    /**
     * 시계열 + 벤치마크. {@code /timeseries}가 날짜로 조인해 쓴다.
     *
     * <p>이 엔드포인트는 원래 시계열을 내려주는 곳이라 크게 읽는 것이 정상이다. 그래도 {@code performance}·{@code execution}·오류 컬럼은
     * 읽지 않는다.
     */
    Optional<BacktestSeries> findTimeseries(Long runId);

    /**
     * 시계열만. {@code /positions}가 역순 스캔에 쓴다.
     *
     * <p>{@link #findTimeseries}를 재사용하지 않는 이유: 벤치마크 시계열도 거래일 수만큼 크고 보유 구성 조회에는 전혀 쓰이지 않는다. 읽지 않을
     * 컬럼은 select에 넣지 않는다.
     */
    Optional<List<EngineOutput.PortfolioSnapshot>> findPortfolioSeries(Long runId);

    /**
     * 비중 기록·비중 출처·예외 기록. {@code /weights}·{@code /exceptions}가 쓴다. 시계열은 읽지 않는다.
     *
     * <p>블록이 저장되지 않은 결과(null 컬럼)는 빈 리스트로 돌려준다 — 결과 행이 없는 것(빈 Optional)과 다르다.
     */
    Optional<BacktestResultBlocks> findBlocks(Long runId);

    /**
     * 거래 내역 한 페이지. 정렬은 {@code (entry_time, id)} 고정이므로 {@code Pageable}의 {@code Sort}를 받지 않는다 — 계약이
     * 정렬을 고정했고 같은 시각의 거래 순서가 흔들리면 페이지 경계에서 행이 중복·누락된다.
     *
     * <p>Spring {@code Page}가 content 쿼리와 count 쿼리를 함께 처리한다. 포트에 프레임워크 타입이 보이지만 application은 이미
     * {@code @Service}·{@code @Transactional}로 Spring에 의존하고, 계층 규칙은 {@code
     * consideringOnlyDependenciesInLayers()}라 프레임워크 타입을 보지 않는다. 감싸는 record를 하나 더 만들 이유가 없다.
     *
     * @param visibleBefore null = 제한 없음. 먼저 취소한 요청은 스냅샷 다음 날 0시(거래소 현지) 전에 <b>청산된</b> 거래만 본다 —
     *     가로지르는 거래와 OPEN 거래는 저장된 값이 실행 종료 시점이라 경계 시점 상태가 아니다(R-04)
     */
    Page<EngineOutput.TradeResult> findTrades(
            Long runId, FindBacktestTradesQuery query, Instant visibleBefore);

    /**
     * 구간 안의 개별 체결을 체결 순서대로 돌려준다(spec 011 DEP-004-011).
     *
     * <p><b>빈 Optional 과 빈 리스트는 다른 뜻이다.</b> 빈 Optional 은 결과가 없거나 체결을 기록하지 않은 결과(이 기능 전의 실행, 옛
     * 워커)이고, 빈 리스트는 기록했는데 그 구간에 체결이 없었다는 뜻이다. 앞의 것을 뒤의 것으로 읽으면 구간 비용이 0 으로 계산된다 — 호출자는 빈 Optional 을
     * "다시 계산해야 하는 실행"으로 다룬다.
     *
     * @param from 포함. null = 제한 없음
     * @param to 포함하지 않음. null = 제한 없음
     */
    Optional<List<EngineOutput.FillResult>> findFills(Long runId, Instant from, Instant to);
}
