package com.ssafy.thorp.backtest.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.ssafy.thorp.backtest.application.repository.BacktestRunRepository;
import com.ssafy.thorp.backtest.domain.model.BacktestRun;
import com.ssafy.thorp.backtest.domain.model.PositionSizingMode;
import com.ssafy.thorp.backtest.domain.vo.BacktestPeriod;
import com.ssafy.thorp.backtest.domain.vo.InstrumentId;
import com.ssafy.thorp.support.IntegrationTestSupport;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * DLQ 집계의 멱등성(spec 004 R-18 ③, T010). 실제 DB 로 "같은 시도를 두 번 세지 않는다"를 고정한다.
 *
 * <p>이 셈이 많이 틀리면 살아 있는 작업이 남았는데도 실행을 {@code FAILED} 로 끊는다. DLQ 소비는 at-least-once 라 커밋 뒤 ACK 이 유실되면
 * 같은 메시지가 다시 오므로, 저장소가 스스로 막아야 한다.
 */
class BacktestRunDeadLetterRecordIT extends IntegrationTestSupport {

    @Autowired private BacktestRunRepository repository;
    @Autowired private TransactionTemplate transactionTemplate;

    private Long newRunId() {
        return transactionTemplate
                .execute(
                        status ->
                                repository.save(
                                        BacktestRun.create(
                                                "FIXED_WEIGHT",
                                                "1",
                                                Map.of(),
                                                List.of(new InstrumentId("091160.XKRX")),
                                                new BacktestPeriod(
                                                        LocalDate.of(2024, 1, 2),
                                                        LocalDate.of(2024, 12, 31)),
                                                new BigDecimal("10000000.00"),
                                                PositionSizingMode.STRATEGY_DEFINED,
                                                null,
                                                null,
                                                42)))
                .getId();
    }

    private boolean record(Long runId, int attempt) {
        return Boolean.TRUE.equals(
                transactionTemplate.execute(
                        status -> repository.recordDeadLetter(runId, attempt, Instant.now())));
    }

    private int dlqCountOf(Long runId) {
        return repository.findById(runId).orElseThrow().getLifecycle().dlqCount();
    }

    @Test
    @DisplayName("같은 시도를 다시 기록하면 세지 않는다 — 커밋 뒤 ACK 이 유실돼 재전달된 경우다")
    void countsEachPublishAttemptOnlyOnce() {
        Long runId = newRunId();

        assertThat(record(runId, 1)).isTrue();
        assertThat(record(runId, 1)).isFalse();

        assertThat(dlqCountOf(runId)).isEqualTo(1);
    }

    @Test
    @DisplayName("다른 시도는 따로 센다 — 중복 실행의 메시지가 각각 죽은 경우다")
    void countsDistinctAttemptsSeparately() {
        Long runId = newRunId();

        assertThat(record(runId, 2)).isTrue();
        assertThat(record(runId, 1)).isTrue();
        assertThat(record(runId, 2)).isFalse();

        // 도착 순서가 뒤바뀌어도 센 것은 둘이다.
        assertThat(dlqCountOf(runId)).isEqualTo(2);
    }
}
