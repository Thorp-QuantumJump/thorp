package com.ssafy.thorp.backtest.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.ssafy.thorp.backtest.application.event.BacktestFinished;
import com.ssafy.thorp.backtest.application.port.message.BacktestEvent;
import com.ssafy.thorp.backtest.application.port.message.FinishedPayload;
import com.ssafy.thorp.backtest.domain.model.RunStatus;
import com.ssafy.thorp.backtest.infrastructure.messaging.MessageFixtures;
import com.ssafy.thorp.support.PostgresTestSupport;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@code BacktestFinished}가 <b>결과 저장 커밋 뒤에</b> 전달되는지 본다(T036, spec 007 research R6).
 *
 * <p>이것이 깨지면 007은 아직 저장되지 않은 결과를 읽는다 — 후보가 "완료"인데 성과가 비어 있는 상태가 되고, 원인이 007 쪽 코드에 없어서 찾기 어렵다. 단위
 * 테스트로는 잡히지 않는다. 발행 시점은 맞고 <b>전달</b> 시점만 트랜잭션에 묶여 있기 때문에, 실제 트랜잭션 경계 안에서 확인해야 한다.
 *
 * <p>그래서 {@code AFTER_COMMIT} 리스너를 하나 붙이고, 트랜잭션 <b>안</b>에서 아직 비어 있는지와 커밋 뒤에 채워지는지를 함께 단언한다.
 */
@SpringBootTest(
        properties = {
            "spring.rabbitmq.listener.simple.auto-startup=false",
            "thorp.backtest.republish.enabled=false",
            "spring.mail.username=test@example.com",
            "spring.mail.password=test"
        })
class BacktestEventServiceEventTest extends PostgresTestSupport {

    private static final String BACKTEST_ID = "BT-20260907-000001";

    /** 시퀀스가 닿지 않는 id를 쓴다. 다른 테스트가 만드는 실행과 PK가 겹치지 않아야 한다. */
    private static final long RUN_ID = 900_001L;

    @TestConfiguration
    static class RecorderConfig {
        @Bean
        FinishedRecorder finishedRecorder() {
            return new FinishedRecorder();
        }
    }

    /** 커밋 뒤에만 받는 수신자. 007의 {@code BacktestFinishedListener}와 같은 규약이다. */
    static class FinishedRecorder {

        private final List<BacktestFinished> received = new ArrayList<>();

        @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
        void onFinished(BacktestFinished event) {
            received.add(event);
        }

        List<BacktestFinished> received() {
            return received;
        }

        void clear() {
            received.clear();
        }
    }

    @Autowired private BacktestEventService eventService;
    @Autowired private TransactionTemplate transactionTemplate;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private FinishedRecorder recorder;

    @BeforeEach
    void prepareRun() {
        recorder.clear();
        // 이 테스트가 쓰는 실행 행만 지우고 다시 만든다. 테이블을 통째로 비우거나 PK를 1로 박으면
        // 시퀀스로 id를 받는 다른 테스트와 충돌한다 — 컨테이너를 공유하기 때문이다.
        jdbcTemplate.update(
                "DELETE FROM backtest_trade WHERE run_id IN"
                        + " (SELECT id FROM backtest_run WHERE backtest_id = ?)",
                BACKTEST_ID);
        jdbcTemplate.update(
                "DELETE FROM backtest_result WHERE run_id IN"
                        + " (SELECT id FROM backtest_run WHERE backtest_id = ?)",
                BACKTEST_ID);
        jdbcTemplate.update(
                "DELETE FROM backtest_request WHERE run_id IN"
                        + " (SELECT id FROM backtest_run WHERE backtest_id = ?)",
                BACKTEST_ID);
        jdbcTemplate.update("DELETE FROM backtest_run WHERE backtest_id = ?", BACKTEST_ID);
        jdbcTemplate.update(
                "INSERT INTO backtest_run (id, backtest_id, strategy_id, strategy_version,"
                        + " parameters, instrument_ids, start_date, end_date, initial_cash,"
                        + " position_sizing_type, random_seed, status, created_at, updated_at)"
                        + " VALUES (?, ?, 'MA_CROSSOVER', '1.0.0', '{}'::jsonb,"
                        + " '[\"005930.XKRX\"]'::jsonb, ?, ?, 10000000.00,"
                        + " 'STRATEGY_DEFINED', 42, 'RUNNING', NOW(), NOW())",
                RUN_ID,
                BACKTEST_ID,
                LocalDate.of(2025, 1, 1),
                LocalDate.of(2025, 6, 30));
    }

    @Test
    @DisplayName("결과 저장 트랜잭션이 커밋되기 전에는 이벤트가 전달되지 않는다")
    void eventIsNotDeliveredBeforeCommit() {
        BacktestEvent<FinishedPayload> finished = MessageFixtures.finishedCompleted();

        transactionTemplate.executeWithoutResult(
                status -> {
                    eventService.handleFinished(finished);
                    // 아직 커밋 전이다. 여기서 받으면 007이 없는 결과를 읽는다.
                    assertThat(recorder.received()).isEmpty();
                });

        assertThat(recorder.received()).hasSize(1);
        assertThat(recorder.received().get(0).backtestJobId()).isEqualTo(RUN_ID);
        assertThat(recorder.received().get(0).backtestId()).isEqualTo(BACKTEST_ID);
        assertThat(recorder.received().get(0).status()).isEqualTo(RunStatus.COMPLETED);
        // 결과 행이 실제로 커밋된 뒤여야 한다 — 이벤트가 가리키는 결과를 수신자가 읽을 수 있다.
        assertThat(
                        jdbcTemplate.queryForObject(
                                "SELECT COUNT(*) FROM backtest_result WHERE run_id = ?",
                                Integer.class,
                                RUN_ID))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("트랜잭션이 되돌아가면 이벤트도 전달되지 않는다")
    void rollbackDeliversNothing() {
        BacktestEvent<FinishedPayload> finished = MessageFixtures.finishedCompleted();

        transactionTemplate.executeWithoutResult(
                status -> {
                    eventService.handleFinished(finished);
                    status.setRollbackOnly();
                });

        assertThat(recorder.received()).isEmpty();
    }
}
