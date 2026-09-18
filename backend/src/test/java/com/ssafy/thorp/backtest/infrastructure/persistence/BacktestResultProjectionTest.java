package com.ssafy.thorp.backtest.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.ssafy.thorp.backtest.application.command.FindBacktestTradesQuery;
import com.ssafy.thorp.backtest.application.command.SaveBacktestResultCommand;
import com.ssafy.thorp.backtest.application.port.message.BacktestEvent;
import com.ssafy.thorp.backtest.application.port.message.EngineOutput;
import com.ssafy.thorp.backtest.application.port.message.FinishedPayload;
import com.ssafy.thorp.backtest.application.port.message.TradeStatus;
import com.ssafy.thorp.backtest.application.result.BacktestResultBlocks;
import com.ssafy.thorp.backtest.application.result.BacktestResultSummary;
import com.ssafy.thorp.backtest.domain.model.BacktestRun;
import com.ssafy.thorp.backtest.domain.model.PositionSizingMode;
import com.ssafy.thorp.backtest.domain.vo.BacktestPeriod;
import com.ssafy.thorp.backtest.domain.vo.InstrumentId;
import com.ssafy.thorp.backtest.infrastructure.messaging.MessageFixtures;
import com.ssafy.thorp.support.RepositoryTestSupport;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.data.domain.Page;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 요약 조회가 {@code portfolio_series}를 읽지 않는다는 것을 <b>행동으로</b> 고정한다.
 *
 * <p>왜 필요한가: 요약은 프론트가 폴링하는 리소스이고 결과 행에는 5년치면 수백 KB인 시계열이 함께 있다. 엔티티를 그대로 읽으면 그것과 벤치마크 시계열이 무조건
 * 딸려오므로 native 프로젝션으로 스칼라만 뽑는다. 그런데 누군가 "단순화"하겠다며 엔티티 조회로 바꿔도 다른 테스트는 전부 통과한다 — 느려질 뿐 결과가 같기 때문이다.
 *
 * <p>그래서 {@code portfolio_series}에 <b>형태가 틀린 JSON</b>을 넣는다. {@code jsonb}로는 유효하지만 {@code
 * List<PortfolioSnapshot>}으로는 역직렬화할 수 없는 값이라, 엔티티를 읽는 순간 터진다. 요약 조회가 성공한다는 단언 하나면 회귀를 잡는 데 충분하다.
 *
 * <p>Hibernate {@code Statistics}로 select 목록을 검사하는 방식은 쓰지 않는다 — 구현 세부에 결합돼 쉽게 깨진다.
 */
class BacktestResultProjectionTest extends RepositoryTestSupport {

    @Autowired private BacktestRunRepositoryImpl jobRepository;
    @Autowired private BacktestResultRepositoryImpl resultRepository;
    @Autowired private TestEntityManager entityManager;
    @Autowired private JdbcTemplate jdbcTemplate;

    private Long saveFixtureResult() {
        Long jobId =
                jobRepository
                        .save(
                                BacktestRun.create(
                                        "MA_CROSSOVER",
                                        "1.0.0",
                                        Map.of("shortWindow", 20),
                                        List.of(new InstrumentId("005930.XKRX")),
                                        new BacktestPeriod(
                                                LocalDate.of(2025, 1, 1),
                                                LocalDate.of(2025, 6, 30)),
                                        new BigDecimal("10000000.00"),
                                        PositionSizingMode.STRATEGY_DEFINED,
                                        null,
                                        new InstrumentId("069500.XKRX"),
                                        42))
                        .getId();
        BacktestEvent<FinishedPayload> event = MessageFixtures.finishedCompleted();
        resultRepository.save(
                new SaveBacktestResultCommand(
                        jobId,
                        event.executionId(),
                        event.eventId(),
                        event.payload().engineOutput(),
                        null));
        entityManager.flush();
        entityManager.clear();
        return jobId;
    }

    @Test
    @DisplayName("portfolio_series가 역직렬화 불가능해도 요약 조회는 성공한다 — 그 컬럼을 읽지 않는다")
    void summaryDoesNotReadPortfolioSeries() {
        Long jobId = saveFixtureResult();
        // jsonb로는 유효하지만 List<PortfolioSnapshot>으로는 읽을 수 없는 값
        jdbcTemplate.update(
                "UPDATE backtest_result SET portfolio_series = '{\"broken\":true}'::jsonb"
                        + " WHERE run_id = ?",
                jobId);

        Optional<BacktestResultSummary> summary = resultRepository.findSummary(jobId);

        assertThat(summary).isPresent();
        assertThat(summary.get().performance().totalReturn()).isEqualByComparingTo("0.166652");
    }

    @Test
    @DisplayName("요약 조회는 저장된 decimal의 값과 scale을 그대로 복원한다")
    void summaryRestoresDecimalScale() {
        Long jobId = saveFixtureResult();
        BacktestEvent<FinishedPayload> event = MessageFixtures.finishedCompleted();

        BacktestResultSummary summary = resultRepository.findSummary(jobId).orElseThrow();

        // record equals는 BigDecimal의 scale까지 본다
        assertThat(summary.performance()).isEqualTo(event.payload().engineOutput().performance());
        assertThat(summary.execution()).isEqualTo(event.payload().engineOutput().execution());
        // v2 FINISHED에는 벤치마크가 없다. 저장 컬럼은 남아 있고 null이다
        assertThat(summary.benchmarkInstrumentId()).isNull();
        assertThat(summary.benchmarkTotalReturn()).isNull();
        assertThat(summary.hasBenchmark()).isFalse();
        // v2 요약 항목(T009): 비용·청산·분석 블록과 예외 건수. 예외 본문은 읽지 않는다
        EngineOutput output = event.payload().engineOutput();
        assertThat(summary.costs()).isEqualTo(output.costs());
        assertThat(summary.liquidation()).isEqualTo(output.liquidation());
        assertThat(summary.analytics()).isEqualTo(output.analytics());
        assertThat(summary.exceptionCount()).isEqualTo(output.exceptions().size());
    }

    @Test
    @DisplayName("비중·예외 블록 조회는 시계열을 읽지 않고 두 리스트와 비중 출처를 돌려준다")
    void findsWeightsAndExceptionsWithoutSeries() {
        Long jobId = saveFixtureResult();
        EngineOutput output = MessageFixtures.finishedCompleted().payload().engineOutput();
        jdbcTemplate.update(
                "UPDATE backtest_result SET portfolio_series = '{\"broken\":true}'::jsonb"
                        + " WHERE run_id = ?",
                jobId);

        BacktestResultBlocks blocks = resultRepository.findBlocks(jobId).orElseThrow();

        assertThat(blocks.weightsSeries()).isEqualTo(output.weightsSeries());
        assertThat(blocks.weightsSource()).isEqualTo(output.weightsSource());
        assertThat(blocks.exceptions()).isEqualTo(output.exceptions());
        assertThat(resultRepository.findBlocks(999_999L)).isEmpty();
    }

    @Test
    @DisplayName("결과 행이 없으면 빈 Optional이다 — 호출자가 Job 상태와 맞춰 해석한다")
    void summaryEmptyWhenNoResultRow() {
        assertThat(resultRepository.findSummary(999_999L)).isEmpty();
    }

    // ---------- 시계열 조회 ----------

    @Test
    @DisplayName("시계열 조회는 두 컬럼을 파싱해 돌려준다")
    void findsTimeseriesWithBenchmark() {
        Long jobId = saveFixtureResult();
        BacktestEvent<FinishedPayload> event = MessageFixtures.finishedCompleted();

        var series = resultRepository.findTimeseries(jobId).orElseThrow();

        assertThat(series.portfolioSeries())
                .isEqualTo(event.payload().engineOutput().portfolioSeries());
        assertThat(series.benchmark()).isNull();
    }

    @Test
    @DisplayName("보유 조회는 시계열 한 컬럼만 읽는다")
    void findsPortfolioSeriesOnly() {
        Long jobId = saveFixtureResult();
        BacktestEvent<FinishedPayload> event = MessageFixtures.finishedCompleted();

        assertThat(resultRepository.findPortfolioSeries(jobId))
                .contains(event.payload().engineOutput().portfolioSeries());
    }

    // ---------- 거래 페이징·필터 ----------

    /**
     * PostgreSQL에서 {@code (:param IS NULL OR ...)} 바인딩이 타입 추론되는지 실측하는 자리이기도 하다. 추론이 안 되면 여기서 깨지고
     * {@code CAST(:instrumentId AS text)}가 필요하다.
     */
    @Test
    @DisplayName("거래는 (entry_time, id) 순서로 페이징된다")
    void pagesTradesInContractOrder() {
        Long jobId = saveFixtureResult();

        Page<EngineOutput.TradeResult> first =
                resultRepository.findTrades(
                        jobId, new FindBacktestTradesQuery(0, 2, null, null), null);
        Page<EngineOutput.TradeResult> second =
                resultRepository.findTrades(
                        jobId, new FindBacktestTradesQuery(1, 2, null, null), null);

        assertThat(first.getTotalElements()).isEqualTo(3);
        assertThat(first.getTotalPages()).isEqualTo(2);
        assertThat(first.getContent())
                .extracting(EngineOutput.TradeResult::tradeId)
                .containsExactly("TRD-000001", "TRD-000002");
        assertThat(second.getContent())
                .extracting(EngineOutput.TradeResult::tradeId)
                .containsExactly("TRD-000003");
        // 페이지 경계에서 행이 중복·누락되지 않는다
        assertThat(first.getContent()).doesNotContainAnyElementsOf(second.getContent());
    }

    @Test
    @DisplayName("instrumentId·status 필터가 각각·조합으로 걸리고 totalElements도 필터 적용값이다")
    void filtersTrades() {
        Long jobId = saveFixtureResult();

        assertThat(
                        resultRepository
                                .findTrades(
                                        jobId,
                                        new FindBacktestTradesQuery(0, 100, "000660.XKRX", null),
                                        null)
                                .getTotalElements())
                .isEqualTo(1);
        assertThat(
                        resultRepository
                                .findTrades(
                                        jobId,
                                        new FindBacktestTradesQuery(0, 100, null, TradeStatus.OPEN),
                                        null)
                                .getTotalElements())
                .isEqualTo(1);
        assertThat(
                        resultRepository
                                .findTrades(
                                        jobId,
                                        new FindBacktestTradesQuery(
                                                0, 100, "005930.XKRX", TradeStatus.CLOSED),
                                        null)
                                .getTotalElements())
                .isEqualTo(1);
        // 있는 종목이지만 그 상태의 거래가 없는 조합
        assertThat(
                        resultRepository
                                .findTrades(
                                        jobId,
                                        new FindBacktestTradesQuery(
                                                0, 100, "000660.XKRX", TradeStatus.OPEN),
                                        null)
                                .getContent())
                .isEmpty();
    }

    @Test
    @DisplayName("취소 요청의 거래 경계: 경계 전에 청산된 거래만 보인다 — 가로지르는 거래와 OPEN 거래는 빠진다")
    void tradesCrossingTheVisibleBoundaryAreExcluded() {
        Long jobId = saveFixtureResult();
        // 픽스처: TRD-000001 01-22 → 03-12 CLOSED, TRD-000002 02-06 → 04-03 CLOSED, TRD-000003 05-14
        // OPEN
        Instant before =
                LocalDate.parse("2025-03-13").atStartOfDay(ZoneId.of("Asia/Seoul")).toInstant();
        FindBacktestTradesQuery all = new FindBacktestTradesQuery(0, 100, null, null);

        assertThat(resultRepository.findTrades(jobId, all, before).getContent())
                .extracting(EngineOutput.TradeResult::tradeId)
                .containsExactly("TRD-000001");
        // 02-06 진입은 경계 전이지만 04-03 청산이 경계 뒤라 status 필터로도 나오지 않는다
        assertThat(
                        resultRepository
                                .findTrades(
                                        jobId,
                                        new FindBacktestTradesQuery(
                                                0, 100, null, TradeStatus.CLOSED),
                                        before)
                                .getTotalElements())
                .isEqualTo(1);
        assertThat(resultRepository.findTrades(jobId, all, null).getTotalElements()).isEqualTo(3);
        // 끝까지 OPEN 인 거래(05-14 진입)는 경계가 그 뒤(06-01)여도 보이지 않는다 — 저장된 OPEN 행은 실행
        // 종료 시점 값(분할·부분 매도로 수량·수수료가 바뀐다)이라 경계 시점 상태가 아니다
        Instant june =
                LocalDate.parse("2025-06-01").atStartOfDay(ZoneId.of("Asia/Seoul")).toInstant();
        assertThat(resultRepository.findTrades(jobId, all, june).getContent())
                .extracting(EngineOutput.TradeResult::tradeId)
                .containsExactly("TRD-000001", "TRD-000002");
    }

    private List<String> tradeIdsIn(Long jobId, LocalDate from, LocalDate to) {
        return resultRepository
                .findTrades(jobId, new FindBacktestTradesQuery(0, 100, null, null, from, to), null)
                .getContent()
                .stream()
                .map(EngineOutput.TradeResult::tradeId)
                .toList();
    }

    @Test
    @DisplayName("기간 필터는 진입 또는 청산이 기간(양 끝 포함) 안에 있는 거래만 남긴다(spec 005 T015)")
    void filtersTradesByPeriod() {
        Long jobId = saveFixtureResult();
        // 픽스처: TRD-000001 01-22 → 03-12 CLOSED, TRD-000002 02-06 → 04-03 CLOSED, TRD-000003 05-14
        // OPEN (모두 2025)

        // 청산만 기간 안
        assertThat(tradeIdsIn(jobId, LocalDate.parse("2025-03-01"), LocalDate.parse("2025-03-31")))
                .containsExactly("TRD-000001");
        // 진입만 기간 안 — 01-22 에 사서 03-12 에 판 거래는 2월 내내 들고만 있어 빠진다
        assertThat(tradeIdsIn(jobId, LocalDate.parse("2025-02-01"), LocalDate.parse("2025-02-28")))
                .containsExactly("TRD-000002");
        // 시작만: 04-03 청산이 경계 당일이라 들어온다
        assertThat(tradeIdsIn(jobId, LocalDate.parse("2025-04-03"), null))
                .containsExactly("TRD-000002", "TRD-000003");
        // 끝만: 01-22 진입이 경계 당일이라 들어온다
        assertThat(tradeIdsIn(jobId, null, LocalDate.parse("2025-01-22")))
                .containsExactly("TRD-000001");
        // 보유만 이어진 기간 — 매매가 없으면 빈 페이지다
        assertThat(tradeIdsIn(jobId, LocalDate.parse("2025-04-10"), LocalDate.parse("2025-05-01")))
                .isEmpty();
        // 기간이 없으면 필터가 없는 것과 같다
        assertThat(tradeIdsIn(jobId, null, null)).hasSize(3);
    }

    @Test
    @DisplayName("거래의 detail을 꺼내면 계약 형태의 거래가 그대로 나온다")
    void tradeContentIsContractShape() {
        Long jobId = saveFixtureResult();
        BacktestEvent<FinishedPayload> event = MessageFixtures.finishedCompleted();

        Page<EngineOutput.TradeResult> page =
                resultRepository.findTrades(
                        jobId, new FindBacktestTradesQuery(0, 100, null, null), null);

        // record equals는 BigDecimal의 scale까지 본다
        assertThat(page.getContent()).isEqualTo(event.payload().engineOutput().trades());
    }
}
