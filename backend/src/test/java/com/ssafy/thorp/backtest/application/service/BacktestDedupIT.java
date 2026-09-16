package com.ssafy.thorp.backtest.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.ssafy.thorp.backtest.application.command.CreateBacktestCommand;
import com.ssafy.thorp.backtest.application.port.message.Scenario;
import com.ssafy.thorp.backtest.application.repository.BacktestRequestRepository;
import com.ssafy.thorp.backtest.application.result.BacktestJobResult;
import com.ssafy.thorp.backtest.domain.model.ExecutionType;
import com.ssafy.thorp.backtest.domain.model.ResultSource;
import com.ssafy.thorp.support.PostgresTestSupport;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 동일 버전 재사용·합류 기록(spec 004 T010, FR-008). 실제 DB 로 "요청 둘이 실행 하나를 공유하고 재사용 기록이 하나 남는다"를 고정한다.
 *
 * <p>브로커는 띄우지 않는다 — 합류·재사용은 <b>발행하지 않는 것</b>이 요점이라 발행 경로를 탈 일이 없다. 접수 자체는 커밋 뒤 발행이므로 브로커가 없어도 202
 * 다(T005 {@code BacktestPublishAfterCommitIT}).
 */
@SpringBootTest(
        properties = {
            "spring.rabbitmq.host=localhost",
            "spring.rabbitmq.port=1",
            "spring.rabbitmq.connection-timeout=1s",
            "spring.rabbitmq.listener.simple.auto-startup=false",
            "thorp.backtest.republish.enabled=false",
            "spring.mail.username=test@example.com",
            "spring.mail.password=test"
        })
class BacktestDedupIT extends PostgresTestSupport {

    @Autowired private BacktestService backtestService;
    @Autowired private BacktestRequestRepository requestRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    private long newUser(String tag) {
        String suffix = tag + (System.nanoTime() % 1_000_000_000L);
        String email = "dedup-" + suffix + "@example.com";
        jdbcTemplate.update(
                "INSERT INTO users (email, password_hash, nickname, phone_number, status,"
                        + " paper_ranking_enabled, live_ranking_enabled, ranking_display_nickname,"
                        + " created_at, updated_at)"
                        + " VALUES (?, 'x', ?, ?, 'ACTIVE', false, false, false, NOW(), NOW())",
                email,
                "u" + suffix,
                suffix);
        return jdbcTemplate.queryForObject(
                "SELECT id FROM users WHERE email = ?", Long.class, email);
    }

    /**
     * 같은 본문. 요청자만 다르므로 동일 버전 키가 같아야 한다.
     *
     * <p><b>기간은 다른 IT와 겹치지 않게 둔다.</b> PostgreSQL 컨테이너를 스위트가 공유하므로, 본문이 같으면 동일 버전 키도 같아서 다른 IT가 만든
     * 실행에 이 요청이 합류한다(그게 이 기능의 정상 동작이다). 그러면 두 테스트가 한 실행을 나눠 갖고 서로의 요청 수를 센다 — 실제로 {@code
     * BacktestCancelFlowIT} 와 겹쳐 깨졌다.
     */
    private static CreateBacktestCommand sameRequest(long requesterId) {
        return requestOver(requesterId, LocalDate.of(2024, 3, 4), LocalDate.of(2024, 10, 31));
    }

    /** 동시 접수 테스트는 또 다른 기간을 쓴다 — 위 테스트의 실행에 합류하면 경합이 일어나지 않는다. */
    private static CreateBacktestCommand concurrentRequest(long requesterId) {
        return requestOver(requesterId, LocalDate.of(2024, 4, 1), LocalDate.of(2024, 9, 30));
    }

    private static CreateBacktestCommand requestOver(
            long requesterId, LocalDate start, LocalDate end) {
        return new CreateBacktestCommand(
                requesterId,
                ExecutionType.SINGLE,
                "SECTOR-ETF-2026-09",
                List.of(
                        new CreateBacktestCommand.AllocationItem(
                                "SEMICONDUCTOR", "091160.XKRX", new BigDecimal("0.4000")),
                        new CreateBacktestCommand.AllocationItem(
                                "BATTERY", "305720.XKRX", new BigDecimal("0.6000"))),
                "FIXED_WEIGHT",
                "1",
                // registryVersion 을 싣지 않는다 — 이 픽스처의 parameters 는 워커가 읽는 이름이고
                // 003 등록표의 축 이름이 아니다. 값을 실으면 접수가 등록표 판정을 걸어 409 다.
                null,
                Map.of("rebalance", "MONTH_END"),
                start,
                end,
                new BigDecimal("10000000.00"),
                null,
                "SPLIT-1",
                null,
                Scenario.FULL_PERIOD,
                null);
    }

    private int reuseEventCount(Long runId) {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM backtest_reuse_event WHERE run_id = ?", Integer.class, runId);
    }

    @Test
    @DisplayName("두 사용자가 같은 본문을 보내면 실행 1·요청 2·재사용 기록 1이다 (FR-008)")
    void twoRequestsShareOneRunAndRecordOneReuse() {
        long first = newUser("a");
        long second = newUser("b");

        BacktestJobResult created = backtestService.create(sameRequest(first));
        BacktestJobResult joined = backtestService.create(sameRequest(second));

        assertThat(created.resultSource()).isEqualTo(ResultSource.NEW);
        assertThat(joined.resultSource()).isEqualTo(ResultSource.JOINED);
        assertThat(joined.backtestId()).isNotEqualTo(created.backtestId());

        Long runId =
                requestRepository.findByBacktestId(created.backtestId()).orElseThrow().getRunId();
        assertThat(requestRepository.findByBacktestId(joined.backtestId()).orElseThrow().getRunId())
                .isEqualTo(runId);
        assertThat(requestRepository.findByRunId(runId)).hasSize(2);
        // 새로 계산한 첫 요청은 기록하지 않는다 — 실행 행 자체가 그 기록이다.
        assertThat(reuseEventCount(runId)).isEqualTo(1);

        Map<String, Object> event =
                jdbcTemplate.queryForMap(
                        "SELECT kind, request_execution_type, at FROM backtest_reuse_event"
                                + " WHERE run_id = ?",
                        runId);
        assertThat(event.get("kind")).isEqualTo("JOINED");
        assertThat(event.get("request_execution_type")).isEqualTo("SINGLE");
        assertThat(event.get("at")).isNotNull();
    }

    @Test
    @DisplayName("같은 본문이 동시에 접수돼도 실행은 하나다 — 둘 다 INSERT 해서 부분 유니크 인덱스에 걸리지 않는다")
    void concurrentIntakeOfTheSameRequestCreatesOneRun() throws Exception {
        long first = newUser("c");
        long second = newUser("d");

        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        BacktestJobResult one;
        BacktestJobResult two;
        try {
            Callable<BacktestJobResult> intakeFirst =
                    () -> {
                        go.await();
                        return backtestService.create(concurrentRequest(first));
                    };
            Callable<BacktestJobResult> intakeSecond =
                    () -> {
                        go.await();
                        return backtestService.create(concurrentRequest(second));
                    };
            Future<BacktestJobResult> a = pool.submit(intakeFirst);
            Future<BacktestJobResult> b = pool.submit(intakeSecond);
            go.countDown();
            // 잠그지 않으면 둘 중 하나가 DataIntegrityViolationException 으로 끝난다.
            one = a.get(30, TimeUnit.SECONDS);
            two = b.get(30, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        Long runId = requestRepository.findByBacktestId(one.backtestId()).orElseThrow().getRunId();
        assertThat(requestRepository.findByBacktestId(two.backtestId()).orElseThrow().getRunId())
                .isEqualTo(runId);
        // 하나는 새로 만들고 하나는 합류한다 — 어느 쪽이 먼저인지는 정하지 않는다.
        assertThat(List.of(one.resultSource(), two.resultSource()))
                .containsExactlyInAnyOrder(ResultSource.NEW, ResultSource.JOINED);
        assertThat(requestRepository.findByRunId(runId)).hasSize(2);
        assertThat(reuseEventCount(runId)).isEqualTo(1);
    }
}
