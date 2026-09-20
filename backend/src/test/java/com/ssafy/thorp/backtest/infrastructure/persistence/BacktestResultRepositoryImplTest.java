package com.ssafy.thorp.backtest.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ssafy.thorp.backtest.application.command.SaveBacktestResultCommand;
import com.ssafy.thorp.backtest.application.port.message.BacktestEvent;
import com.ssafy.thorp.backtest.application.port.message.EngineOutput;
import com.ssafy.thorp.backtest.application.port.message.FinishedPayload;
import com.ssafy.thorp.backtest.application.port.message.TradeStatus;
import com.ssafy.thorp.backtest.domain.model.BacktestRun;
import com.ssafy.thorp.backtest.domain.model.PositionSizingMode;
import com.ssafy.thorp.backtest.domain.vo.BacktestPeriod;
import com.ssafy.thorp.backtest.domain.vo.InstrumentId;
import com.ssafy.thorp.backtest.infrastructure.messaging.MessageFixtures;
import com.ssafy.thorp.support.RepositoryTestSupport;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;

/**
 * 계약 픽스처를 그대로 저장하고 읽는다. 저장 구조가 계약과 어긋나면 여기서 깨진다.
 *
 * <p>JSON 컬럼의 단언은 record {@code equals}로 한다. record 안의 {@code BigDecimal.equals}는 scale까지 비교하므로 소수
 * 자릿수가 하나라도 바뀌면 실패한다. Flyway가 만든 스키마 위에서 {@code validate}로 돌기 때문에 마이그레이션과 엔티티가 어긋나도 여기서 깨진다.
 */
class BacktestResultRepositoryImplTest extends RepositoryTestSupport {

    @Autowired private BacktestRunRepositoryImpl jobRepository;
    @Autowired private BacktestResultRepositoryImpl resultRepository;
    @Autowired private BacktestTradeJpaRepository tradeRepository;
    @Autowired private TestEntityManager entityManager;

    private Long newJobId() {
        return jobRepository
                .save(
                        BacktestRun.create(
                                "MA_CROSSOVER",
                                "1.0.0",
                                Map.of("shortWindow", 20),
                                List.of(new InstrumentId("005930.XKRX")),
                                new BacktestPeriod(
                                        LocalDate.of(2025, 1, 1), LocalDate.of(2025, 6, 30)),
                                new BigDecimal("10000000.00"),
                                PositionSizingMode.STRATEGY_DEFINED,
                                null,
                                new InstrumentId("069500.XKRX"),
                                42))
                .getId();
    }

    private static SaveBacktestResultCommand command(
            Long jobId, BacktestEvent<FinishedPayload> event) {
        return new SaveBacktestResultCommand(
                jobId, event.executionId(), event.eventId(), event.payload().engineOutput(), null);
    }

    private void saveAndClear(SaveBacktestResultCommand command) {
        resultRepository.save(command);
        entityManager.flush();
        entityManager.clear();
    }

    @Test
    @DisplayName("COMPLETED 픽스처를 저장하면 결과 행의 JSON 컬럼이 계약 값과 scale까지 같다")
    void savesCompletedFixture() {
        BacktestEvent<FinishedPayload> event = MessageFixtures.finishedCompleted();
        EngineOutput output = event.payload().engineOutput();
        Long jobId = newJobId();

        saveAndClear(command(jobId, event));

        BacktestResultJpaEntity result = entityManager.find(BacktestResultJpaEntity.class, jobId);
        assertThat(result).isNotNull();
        assertThat(result.getExecutionId()).isEqualTo("EXEC-000001");
        assertThat(result.getFinishedEventId()).isEqualTo("EVT-000009");
        assertThat(result.getStartedAt()).isEqualTo(output.run().startedAt());
        assertThat(result.getFinishedAt()).isEqualTo(output.run().finishedAt());
        assertThat(result.getErrorCode()).isNull();
        assertThat(result.getErrorMessage()).isNull();
        assertThat(result.getPerformance()).isEqualTo(output.performance());
        assertThat(result.getExecution()).isEqualTo(output.execution());
        assertThat(result.getPortfolioSeries()).isEqualTo(output.portfolioSeries());
        assertThat(result.getBenchmark()).isNull();
        assertThat(result.getDiagnostics()).isEqualTo(output.diagnostics());
        // v2 블록(T009). 표시용이라 전부 JSONB 이고 weights_source 만 필터용 스칼라다
        assertThat(result.getWeightsSeries()).isEqualTo(output.weightsSeries());
        assertThat(result.getWeightsSource()).isEqualTo(output.weightsSource());
        assertThat(result.getCosts()).isEqualTo(output.costs());
        assertThat(result.getLiquidation()).isEqualTo(output.liquidation());
        assertThat(result.getAnalytics()).isEqualTo(output.analytics());
        assertThat(result.getExceptions()).isEqualTo(output.exceptions());
        assertThat(result.getVersions()).isEqualTo(output.versions());
        assertThat(result.getCreatedAt()).isNotNull();
        // 결과 행은 실행 행 없이도 재현 기준을 다 가져야 한다(data-model: run.versions 사본, T018).
        // 엔티티 equals 는 필드가 빠져도 양쪽이 같이 빠지면 통과하므로 DB 의 JSONB 키를 직접 본다.
        assertThat(
                        entityManager
                                .getEntityManager()
                                .createNativeQuery(
                                        "SELECT versions ->> 'evaluationSplit' FROM backtest_result"
                                                + " WHERE run_id = :runId")
                                .setParameter("runId", jobId)
                                .getSingleResult())
                .isEqualTo("SPLIT-1");
    }

    @Test
    @DisplayName("거래는 행으로 저장되고 detail JSON이 계약의 거래와 같다")
    void savesTradesAsRows() {
        BacktestEvent<FinishedPayload> event = MessageFixtures.finishedCompleted();
        List<EngineOutput.TradeResult> trades = event.payload().engineOutput().trades();
        Long jobId = newJobId();

        saveAndClear(command(jobId, event));

        List<BacktestTradeJpaEntity> saved =
                tradeRepository.findByJobIdOrderByEntryTimeAscIdAsc(jobId);
        assertThat(saved).hasSameSizeAs(trades);
        assertThat(saved)
                .extracting(BacktestTradeJpaEntity::getDetail)
                .containsExactlyElementsOf(trades);
        for (int i = 0; i < trades.size(); i++) {
            EngineOutput.TradeResult expected = trades.get(i);
            BacktestTradeJpaEntity actual = saved.get(i);
            assertThat(actual.getTradeId()).isEqualTo(expected.tradeId());
            assertThat(actual.getInstrumentId()).isEqualTo(expected.instrumentId());
            assertThat(actual.getSide()).isEqualTo(expected.side());
            assertThat(actual.getStatus()).isEqualTo(expected.status());
            assertThat(actual.getEntryTime()).isEqualTo(expected.entryTime());
            assertThat(actual.getExitTime()).isEqualTo(expected.exitTime());
        }
        assertThat(saved)
                .filteredOn(trade -> trade.getStatus() == TradeStatus.OPEN)
                .allSatisfy(trade -> assertThat(trade.getExitTime()).isNull());
    }

    @Test
    @DisplayName("개별 체결은 행으로 저장되고 구간 조회가 체결 순서대로 계약 값 그대로 돌려준다")
    void savesFillsAsRowsAndReadsThemByPeriod() {
        BacktestEvent<FinishedPayload> event = MessageFixtures.finishedCompleted();
        List<EngineOutput.FillResult> fills = event.payload().engineOutput().fills();
        assertThat(fills).hasSizeGreaterThan(2);
        Long jobId = newJobId();

        saveAndClear(command(jobId, event));

        assertThat(resultRepository.findFills(jobId, null, null)).contains(fills);
        // from 은 포함, to 는 포함하지 않는다 — 두 번째 체결 시각부터 마지막 체결 시각 앞까지
        Instant from = fills.get(1).time();
        Instant to = fills.get(fills.size() - 1).time();
        assertThat(resultRepository.findFills(jobId, from, to))
                .contains(fills.subList(1, fills.size() - 1));
        // 한쪽만 null 이어도 그쪽 제한만 풀린다
        assertThat(resultRepository.findFills(jobId, null, to))
                .contains(fills.subList(0, fills.size() - 1));
        assertThat(resultRepository.findFills(jobId, from, null))
                .contains(fills.subList(1, fills.size()));
        // 기록은 했는데 그 구간에 체결이 없으면 빈 리스트다 — "기록하지 않았다"(빈 Optional)와 다르다
        assertThat(resultRepository.findFills(jobId, to.plusSeconds(1), null)).contains(List.of());
    }

    @Test
    @DisplayName("체결을 기록하지 않은 결과와 결과가 없는 실행의 체결 조회는 빈 Optional이다")
    void distinguishesUnrecordedFillsFromNoFills() {
        BacktestEvent<FinishedPayload> event = MessageFixtures.finishedCompleted();
        EngineOutput output = event.payload().engineOutput();
        EngineOutput withoutFills =
                new EngineOutput(
                        output.run(),
                        output.performance(),
                        output.portfolioSeries(),
                        output.weightsSeries(),
                        output.weightsSource(),
                        output.trades(),
                        null,
                        output.execution(),
                        output.costs(),
                        output.liquidation(),
                        output.analytics(),
                        output.exceptions(),
                        output.versions(),
                        output.diagnostics());
        Long jobId = newJobId();

        saveAndClear(
                new SaveBacktestResultCommand(
                        jobId, event.executionId(), event.eventId(), withoutFills, null));

        assertThat(resultRepository.findFills(jobId, null, null)).isEmpty();
        assertThat(resultRepository.findFills(newJobId(), null, null)).isEmpty();
    }

    @Test
    @DisplayName("FAILED 픽스처는 오류 정보만 채우고 성과·집계·벤치마크는 null, 거래는 0건이다")
    void savesFailedFixture() {
        BacktestEvent<FinishedPayload> event = MessageFixtures.finishedFailed();
        Long jobId = newJobId();

        saveAndClear(command(jobId, event));

        BacktestResultJpaEntity result = entityManager.find(BacktestResultJpaEntity.class, jobId);
        assertThat(result.getErrorCode()).isEqualTo("DATA_INSUFFICIENT");
        assertThat(result.getErrorMessage()).contains("000660.XKRX");
        assertThat(result.getPerformance()).isNull();
        assertThat(result.getExecution()).isNull();
        assertThat(result.getBenchmark()).isNull();
        assertThat(result.getPortfolioSeries()).isEmpty();
        assertThat(result.getDiagnostics().warnings()).isEmpty();
        assertThat(tradeRepository.findByJobIdOrderByEntryTimeAscIdAsc(jobId)).isEmpty();
    }

    @Test
    @DisplayName("같은 Job에 같은 tradeId가 두 번 오면 UNIQUE 제약에 걸린다")
    void rejectsDuplicateTradeId() {
        BacktestEvent<FinishedPayload> event = MessageFixtures.finishedCompleted();
        EngineOutput output = event.payload().engineOutput();
        EngineOutput.TradeResult first = output.trades().get(0);
        EngineOutput duplicated =
                new EngineOutput(
                        output.run(),
                        output.performance(),
                        output.portfolioSeries(),
                        output.weightsSeries(),
                        output.weightsSource(),
                        List.of(first, first),
                        output.fills(),
                        output.execution(),
                        output.costs(),
                        output.liquidation(),
                        output.analytics(),
                        output.exceptions(),
                        output.versions(),
                        output.diagnostics());
        Long jobId = newJobId();

        resultRepository.save(
                new SaveBacktestResultCommand(
                        jobId, event.executionId(), event.eventId(), duplicated, null));

        assertThatThrownBy(() -> entityManager.flush()).isInstanceOf(Exception.class);
    }

    @Test
    @DisplayName("없는 Job을 가리키면 FK 제약에 걸린다")
    void rejectsUnknownJob() {
        resultRepository.save(command(999_999L, MessageFixtures.finishedFailed()));

        assertThatThrownBy(() -> entityManager.flush()).isInstanceOf(Exception.class);
    }

    @Test
    @DisplayName("같은 Job에 결과를 두 번 저장하면 PK 충돌로 실패한다 — 호출자가 상태로 먼저 걸러낸다")
    void rejectsSecondResultForSameJob() {
        Long jobId = newJobId();
        saveAndClear(command(jobId, MessageFixtures.finishedFailed()));

        resultRepository.save(command(jobId, MessageFixtures.finishedCompleted()));

        assertThatThrownBy(() -> entityManager.flush()).isInstanceOf(Exception.class);
    }
}
