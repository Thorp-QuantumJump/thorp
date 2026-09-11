package com.ssafy.thorp.backtest.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.ssafy.thorp.backtest.application.port.BacktestProgressNotifier;
import com.ssafy.thorp.backtest.application.port.message.BacktestEvent;
import com.ssafy.thorp.backtest.application.port.message.FinishedPayload;
import com.ssafy.thorp.backtest.application.port.message.ProgressPayload;
import com.ssafy.thorp.backtest.application.port.message.StartedPayload;
import com.ssafy.thorp.backtest.application.repository.BacktestRunRepository;
import com.ssafy.thorp.backtest.application.service.BacktestEventService;
import com.ssafy.thorp.backtest.domain.model.BacktestRun;
import com.ssafy.thorp.backtest.domain.model.PositionSizingMode;
import com.ssafy.thorp.backtest.domain.model.RunStatus;
import com.ssafy.thorp.backtest.domain.vo.BacktestPeriod;
import com.ssafy.thorp.backtest.domain.vo.InstrumentId;
import com.ssafy.thorp.support.PostgresTestSupport;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * 서비스 계층을 직접 호출해 실제 트랜잭션 경계에서 Job 생성 → STARTED → FINISHED 전이와 저장, 멱등성을 확인한다.
 *
 * <p>브로커 컨테이너는 띄우지 않는다. 죽은 포트를 브로커로 지정하고 리스너를 끈다. DB는 {@link PostgresTestSupport}의 PostgreSQL
 * 컨테이너다. 브로커를 경유하는 확인은 {@code BacktestEventListenerIT}가 한다.
 */
@SpringBootTest(
        properties = {
            "spring.rabbitmq.host=localhost",
            "spring.rabbitmq.port=1",
            "spring.rabbitmq.connection-timeout=1s",
            "spring.rabbitmq.listener.simple.auto-startup=false",
            // 전체 컨텍스트라 메일 발송 빈이 만들어진다. 운영에서만 주입되는 값이므로 더미를 넣는다.
            "spring.mail.username=test@example.com",
            "spring.mail.password=test"
        })
class BacktestEventFlowIT extends PostgresTestSupport {

    @Autowired private BacktestEventService service;
    @Autowired private BacktestRunRepository jobRepository;
    @Autowired private JdbcTemplate jdbcTemplate;
    @MockitoBean private BacktestProgressNotifier progressNotifier;

    private BacktestRun newJob() {
        return jobRepository.save(
                BacktestRun.create(
                        "MA_CROSSOVER",
                        "1.0.0",
                        Map.of("shortWindow", 20),
                        List.of(new InstrumentId("005930.XKRX")),
                        new BacktestPeriod(LocalDate.of(2025, 1, 1), LocalDate.of(2025, 6, 30)),
                        new BigDecimal("10000000.00"),
                        PositionSizingMode.STRATEGY_DEFINED,
                        null,
                        new InstrumentId("069500.XKRX"),
                        42));
    }

    private static BacktestEvent<StartedPayload> started(BacktestRun job) {
        return MessageFixtures.parse(
                MessageFixtures.withRunId(MessageFixtures.STARTED, job.getBacktestId().value()),
                MessageFixtures.STARTED_EVENT);
    }

    private static BacktestEvent<ProgressPayload> progress(BacktestRun job) {
        return MessageFixtures.parse(
                MessageFixtures.withRunId(MessageFixtures.PROGRESS, job.getBacktestId().value()),
                MessageFixtures.PROGRESS_EVENT);
    }

    private static BacktestEvent<FinishedPayload> finished(BacktestRun job, String file) {
        return MessageFixtures.parse(
                MessageFixtures.withRunId(file, job.getBacktestId().value()),
                MessageFixtures.FINISHED_EVENT);
    }

    private RunStatus statusOf(BacktestRun job) {
        return jobRepository
                .findByBacktestId(job.getBacktestId().value())
                .orElseThrow()
                .getStatus();
    }

    private Integer count(String table, Long jobId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + table + " WHERE run_id = ?", Integer.class, jobId);
    }

    private String resultColumn(String column, Long jobId) {
        return jdbcTemplate.queryForObject(
                "SELECT " + column + " FROM backtest_result WHERE run_id = ?", String.class, jobId);
    }

    @Test
    @DisplayName("Job 생성 → STARTED → FINISHED(COMPLETED): 상태가 움직이고 결과·거래가 저장된다")
    void fullFlow() {
        BacktestRun job = newJob();
        assertThat(statusOf(job)).isEqualTo(RunStatus.QUEUED);

        service.handleStarted(started(job));
        assertThat(statusOf(job)).isEqualTo(RunStatus.RUNNING);

        service.handleFinished(finished(job, MessageFixtures.FINISHED_COMPLETED));
        assertThat(statusOf(job)).isEqualTo(RunStatus.COMPLETED);
        assertThat(count("backtest_result", job.getId())).isEqualTo(1);
        assertThat(count("backtest_trade", job.getId())).isEqualTo(3);
        assertThat(resultColumn("finished_event_id", job.getId())).isEqualTo("EVT-000009");
        assertThat(resultColumn("error_code", job.getId())).isNull();
        // 슬라이스 테스트가 아닌 전체 컨텍스트에서도 JSON 직렬화 설정이 살아 있어 decimal이 문자열로 저장된다
        assertThat(resultColumn("jsonb_typeof(performance -> 'initialEquity')", job.getId()))
                .isEqualTo("string");
    }

    @Test
    @DisplayName("FINISHED가 두 번 오면 두 번째는 무시된다(멱등·첫 결과 채택)")
    void secondFinishedIsIgnored() {
        BacktestRun job = newJob();
        service.handleStarted(started(job));
        service.handleFinished(finished(job, MessageFixtures.FINISHED_COMPLETED));

        // 다른 eventId·다른 결과(FAILED)로 다시 와도 첫 결과가 남는다
        service.handleFinished(finished(job, MessageFixtures.FINISHED_FAILED));

        assertThat(statusOf(job)).isEqualTo(RunStatus.COMPLETED);
        assertThat(count("backtest_result", job.getId())).isEqualTo(1);
        assertThat(count("backtest_trade", job.getId())).isEqualTo(3);
        assertThat(resultColumn("finished_event_id", job.getId())).isEqualTo("EVT-000009");
        assertThat(resultColumn("error_code", job.getId())).isNull();
    }

    @Test
    @DisplayName("STARTED 없이 FINISHED(FAILED)가 와도 결과가 저장되고 FAILED가 된다")
    void finishedWithoutStarted() {
        BacktestRun job = newJob();

        service.handleFinished(finished(job, MessageFixtures.FINISHED_FAILED));

        assertThat(statusOf(job)).isEqualTo(RunStatus.FAILED);
        assertThat(count("backtest_result", job.getId())).isEqualTo(1);
        assertThat(count("backtest_trade", job.getId())).isZero();
        assertThat(resultColumn("error_code", job.getId())).isEqualTo("DATA_INSUFFICIENT");
    }

    @Test
    @DisplayName("PROGRESS는 RUNNING일 때만 notifier로 가고 최종 상태 이후에는 가지 않는다")
    void progressOnlyWhileRunning() {
        BacktestRun job = newJob();
        service.handleProgress(progress(job)); // QUEUED: 무시
        verifyNoInteractions(progressNotifier);

        service.handleStarted(started(job));
        BacktestEvent<ProgressPayload> running = progress(job);
        service.handleProgress(running);
        verify(progressNotifier).notifyProgress(running);

        service.handleFinished(finished(job, MessageFixtures.FINISHED_COMPLETED));
        service.handleProgress(progress(job)); // COMPLETED: 무시
        verify(progressNotifier).notifyProgress(running); // 여전히 1회
    }

    @Test
    @DisplayName("모르는 backtestId의 이벤트는 예외 없이 버려진다")
    void unknownBacktestIdIsDropped() {
        String unknown = "BT-20991231-999999";

        assertThatCode(
                        () -> {
                            service.handleStarted(
                                    MessageFixtures.parse(
                                            MessageFixtures.withRunId(
                                                    MessageFixtures.STARTED, unknown),
                                            MessageFixtures.STARTED_EVENT));
                            service.handleFinished(
                                    MessageFixtures.parse(
                                            MessageFixtures.withRunId(
                                                    MessageFixtures.FINISHED_COMPLETED, unknown),
                                            MessageFixtures.FINISHED_EVENT));
                        })
                .doesNotThrowAnyException();
        assertThat(jobRepository.findByBacktestId(unknown)).isEmpty();
    }
}
