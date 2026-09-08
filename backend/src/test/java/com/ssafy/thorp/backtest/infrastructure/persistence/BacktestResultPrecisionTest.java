package com.ssafy.thorp.backtest.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.ssafy.thorp.backtest.application.command.SaveBacktestResultCommand;
import com.ssafy.thorp.backtest.application.port.message.BenchmarkResult;
import com.ssafy.thorp.backtest.application.port.message.EngineOutput;
import com.ssafy.thorp.backtest.application.port.message.RunStatus;
import com.ssafy.thorp.backtest.application.port.message.TradeSide;
import com.ssafy.thorp.backtest.application.port.message.TradeStatus;
import com.ssafy.thorp.backtest.domain.model.BacktestJob;
import com.ssafy.thorp.backtest.domain.model.PositionSizingMode;
import com.ssafy.thorp.backtest.domain.vo.BacktestPeriod;
import com.ssafy.thorp.backtest.domain.vo.InstrumentId;
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
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * decimal이 어디서도 반올림·정규화되지 않는지 실제 PostgreSQL {@code jsonb}에서 확인한다.
 *
 * <p>계약은 최대 자릿수를 정하지 않았다. 그래서 어떤 컬럼 scale도 근거가 없고, 값과 scale을 그대로 보존하는 것만이 맞다. 여기서 반올림이 일어나면 그것을
 * 정상으로 고정하지 않고 저장 방식을 고친다.
 *
 * <p>{@code jsonb}는 JSON 숫자를 {@code numeric}으로 정규화한다(지수 표기가 사라지는 등). 메시지 ObjectMapper가 decimal을
 * 문자열로 쓰기 때문에 그 경로를 타지 않는다 — 저장된 값이 JSON 문자열인지도 함께 확인한다.
 */
@DataJpaTest
@Import({
    JpaAuditingConfig.class,
    MessageJsonPersistenceConfig.class,
    BacktestJobRepositoryImpl.class,
    BacktestResultRepositoryImpl.class
})
class BacktestResultPrecisionTest extends PostgresTestSupport {

    /** 소수 20자리. NUMERIC(28,10)·(38,18) 모두 반올림하는 값이다. */
    private static final BigDecimal LONG_SCALE = new BigDecimal("53726.85123456789012345678");

    /** 후행 0. scale 2가 유지돼야 한다. */
    private static final BigDecimal TRAILING_ZERO = new BigDecimal("1.50");

    /** {@code toString()}이 지수 표기("1E-7")가 되는 값. */
    private static final BigDecimal TINY = new BigDecimal("0.0000001");

    private static final Instant T0 = Instant.parse("2025-01-02T00:00:00Z");
    private static final LocalDate D0 = LocalDate.of(2025, 1, 2);

    @TestConfiguration
    static class FixedClockConfig {
        @Bean
        Clock clock() {
            return Clock.fixed(Instant.parse("2026-09-07T03:00:00Z"), ZoneId.of("Asia/Seoul"));
        }
    }

    @Autowired private BacktestJobRepositoryImpl jobRepository;
    @Autowired private BacktestResultRepositoryImpl resultRepository;
    @Autowired private BacktestTradeJpaRepository tradeRepository;
    @Autowired private TestEntityManager entityManager;
    @Autowired private JdbcTemplate jdbcTemplate;

    private static EngineOutput output() {
        var performance =
                new EngineOutput.PerformanceResult(
                        LONG_SCALE,
                        TRAILING_ZERO,
                        "KRW",
                        TINY,
                        LONG_SCALE.negate(),
                        TINY.negate(),
                        null,
                        new BigDecimal("0.000000"),
                        1,
                        null);
        var position =
                new EngineOutput.PositionSnapshot(
                        "005930.XKRX", TRAILING_ZERO, LONG_SCALE, TINY, LONG_SCALE.negate());
        var snapshot =
                new EngineOutput.PortfolioSnapshot(
                        D0,
                        LONG_SCALE,
                        TRAILING_ZERO,
                        TINY,
                        null,
                        TRAILING_ZERO,
                        LONG_SCALE.negate(),
                        List.of(position));
        var trade =
                new EngineOutput.TradeResult(
                        "TRD-1",
                        "005930.XKRX",
                        TradeSide.LONG,
                        TradeStatus.CLOSED,
                        T0,
                        T0.plusSeconds(86_400),
                        LONG_SCALE,
                        TRAILING_ZERO,
                        TINY,
                        LONG_SCALE,
                        TRAILING_ZERO,
                        TINY,
                        LONG_SCALE.negate(),
                        TINY.negate());
        var execution = new EngineOutput.ExecutionResult(1, 1, 0, LONG_SCALE, TINY);
        return new EngineOutput(
                new EngineOutput.RunResult(
                        "BT-1", RunStatus.COMPLETED, T0, T0.plusSeconds(1), null),
                performance,
                List.of(snapshot),
                List.of(trade),
                execution,
                new EngineOutput.Diagnostics(List.of()));
    }

    private static BenchmarkResult benchmark() {
        return new BenchmarkResult(
                "069500.XKRX",
                TINY,
                List.of(new BenchmarkResult.BenchmarkPoint(D0, LONG_SCALE, TRAILING_ZERO)));
    }

    @Test
    @DisplayName("소수 20자리·후행 0·지수 표기 값이 모든 JSON 컬럼에서 scale까지 그대로 왕복한다")
    void decimalsRoundTripExactly() {
        Long jobId =
                jobRepository
                        .save(
                                BacktestJob.create(
                                        "MA_CROSSOVER",
                                        "1.0.0",
                                        Map.of(),
                                        List.of(new InstrumentId("005930.XKRX")),
                                        new BacktestPeriod(D0, D0.plusMonths(1)),
                                        LONG_SCALE,
                                        PositionSizingMode.STRATEGY_DEFINED,
                                        null,
                                        null,
                                        0))
                        .getId();
        EngineOutput output = output();
        BenchmarkResult benchmark = benchmark();

        resultRepository.save(
                new SaveBacktestResultCommand(jobId, "EXEC-1", "EVT-1", output, benchmark));
        entityManager.flush();
        entityManager.clear();

        BacktestResultJpaEntity result = entityManager.find(BacktestResultJpaEntity.class, jobId);
        // record equals → BigDecimal.equals → scale까지 같아야 통과
        assertThat(result.getPerformance()).isEqualTo(output.performance());
        assertThat(result.getExecution()).isEqualTo(output.execution());
        assertThat(result.getPortfolioSeries()).isEqualTo(output.portfolioSeries());
        assertThat(result.getBenchmark()).isEqualTo(benchmark);
        assertThat(tradeRepository.findByJobIdOrderByEntryTimeAscIdAsc(jobId))
                .extracting(BacktestTradeJpaEntity::getDetail)
                .containsExactly(output.trades().get(0));
    }

    @Test
    @DisplayName("decimal은 jsonb 안에 문자열로 저장되어 숫자 정규화를 타지 않는다")
    void decimalsAreStoredAsJsonStrings() {
        Long jobId =
                jobRepository
                        .save(
                                BacktestJob.create(
                                        "MA_CROSSOVER",
                                        "1.0.0",
                                        Map.of(),
                                        List.of(new InstrumentId("005930.XKRX")),
                                        new BacktestPeriod(D0, D0.plusMonths(1)),
                                        LONG_SCALE,
                                        PositionSizingMode.STRATEGY_DEFINED,
                                        null,
                                        null,
                                        0))
                        .getId();
        resultRepository.save(
                new SaveBacktestResultCommand(jobId, "EXEC-1", "EVT-1", output(), benchmark()));
        entityManager.flush();

        String type =
                jdbcTemplate.queryForObject(
                        "SELECT jsonb_typeof(performance -> 'initialEquity') FROM backtest_result"
                                + " WHERE backtest_job_id = ?",
                        String.class,
                        jobId);
        String stored =
                jdbcTemplate.queryForObject(
                        "SELECT performance ->> 'initialEquity' FROM backtest_result"
                                + " WHERE backtest_job_id = ?",
                        String.class,
                        jobId);
        String tiny =
                jdbcTemplate.queryForObject(
                        "SELECT detail ->> 'quantity' FROM backtest_trade WHERE backtest_job_id = ?",
                        String.class,
                        jobId);

        assertThat(type).isEqualTo("string");
        assertThat(stored).isEqualTo("53726.85123456789012345678");
        assertThat(tiny).as("지수 표기가 아니라 plain 문자열").isEqualTo("0.0000001");
    }
}
