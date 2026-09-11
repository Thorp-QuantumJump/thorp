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
import com.ssafy.thorp.backtest.infrastructure.messaging.MessageJsonPersistenceConfig;
import com.ssafy.thorp.common.config.JpaAuditingConfig;
import com.ssafy.thorp.support.PostgresTestSupport;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

/**
 * 계약 픽스처를 그대로 저장하고 읽는다. 저장 구조가 계약과 어긋나면 여기서 깨진다.
 *
 * <p>JSON 컬럼의 단언은 record {@code equals}로 한다. record 안의 {@code BigDecimal.equals}는 scale까지 비교하므로 소수
 * 자릿수가 하나라도 바뀌면 실패한다. Flyway가 만든 스키마 위에서 {@code validate}로 돌기 때문에 마이그레이션과 엔티티가 어긋나도 여기서 깨진다.
 */
@DataJpaTest
@Import({
    JpaAuditingConfig.class,
    MessageJsonPersistenceConfig.class,
    BacktestRunRepositoryImpl.class,
    BacktestResultRepositoryImpl.class
})
class BacktestResultRepositoryImplTest extends PostgresTestSupport {

    @TestConfiguration
    static class FixedClockConfig {
        @Bean
        Clock clock() {
            return Clock.fixed(Instant.parse("2026-09-07T03:00:00Z"), ZoneId.of("Asia/Seoul"));
        }
    }

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
        assertThat(result.getCreatedAt()).isNotNull();
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
