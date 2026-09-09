package com.ssafy.thorp.backtest.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.ssafy.thorp.backtest.application.command.SaveBacktestResultCommand;
import com.ssafy.thorp.backtest.application.port.message.BacktestEvent;
import com.ssafy.thorp.backtest.application.port.message.FinishedPayload;
import com.ssafy.thorp.backtest.application.result.BacktestResultSummary;
import com.ssafy.thorp.backtest.domain.model.BacktestJob;
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
import java.util.Optional;
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
@DataJpaTest
@Import({
    JpaAuditingConfig.class,
    MessageJsonPersistenceConfig.class,
    BacktestJobRepositoryImpl.class,
    BacktestResultRepositoryImpl.class
})
class BacktestResultProjectionTest extends PostgresTestSupport {

    @TestConfiguration
    static class FixedClockConfig {
        @Bean
        Clock clock() {
            return Clock.fixed(Instant.parse("2026-09-07T03:00:00Z"), ZoneId.of("Asia/Seoul"));
        }
    }

    @Autowired private BacktestJobRepositoryImpl jobRepository;
    @Autowired private BacktestResultRepositoryImpl resultRepository;
    @Autowired private TestEntityManager entityManager;
    @Autowired private JdbcTemplate jdbcTemplate;

    private Long saveFixtureResult() {
        Long jobId =
                jobRepository
                        .save(
                                BacktestJob.create(
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
                        event.payload().benchmark()));
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
                        + " WHERE backtest_job_id = ?",
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
        // 벤치마크는 jsonb에서 스칼라 두 개만 뽑는다. ::numeric 캐스팅을 하지 않으므로 scale이 보존된다
        assertThat(summary.benchmarkInstrumentId())
                .isEqualTo(event.payload().benchmark().instrumentId());
        assertThat(summary.benchmarkTotalReturn())
                .isEqualTo(event.payload().benchmark().totalReturn());
        assertThat(summary.hasBenchmark()).isTrue();
    }

    @Test
    @DisplayName("결과 행이 없으면 빈 Optional이다 — 호출자가 Job 상태와 맞춰 해석한다")
    void summaryEmptyWhenNoResultRow() {
        assertThat(resultRepository.findSummary(999_999L)).isEmpty();
    }
}
