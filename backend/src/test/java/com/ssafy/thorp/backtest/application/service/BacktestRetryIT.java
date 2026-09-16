package com.ssafy.thorp.backtest.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ssafy.thorp.backtest.application.command.CreateBacktestCommand;
import com.ssafy.thorp.backtest.application.port.message.Scenario;
import com.ssafy.thorp.backtest.application.repository.BacktestRequestRepository;
import com.ssafy.thorp.backtest.application.repository.BacktestRunRepository;
import com.ssafy.thorp.backtest.application.result.BacktestJobResult;
import com.ssafy.thorp.backtest.domain.model.BacktestRequest;
import com.ssafy.thorp.backtest.domain.model.BacktestRun;
import com.ssafy.thorp.backtest.domain.model.ExecutionType;
import com.ssafy.thorp.backtest.domain.model.ResultSource;
import com.ssafy.thorp.backtest.domain.model.RunStatus;
import com.ssafy.thorp.common.exception.BusinessException;
import com.ssafy.thorp.common.exception.ErrorCode;
import com.ssafy.thorp.support.PostgresTestSupport;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 재시도(spec 004 T010). 실제 DB 로 "저장된 요청 사본이 같은 조건을 되살리는가"를 고정한다.
 *
 * <p>핵심 단언은 <b>동일 버전 키가 같다</b>는 것이다. 사본은 JSONB 로 저장되면서 decimal 이 문자열이 되므로, 한 칸이라도 잘못 읽으면 키가 달라진다 —
 * 키가 같다는 것은 결과 숫자를 바꾸는 값이 전부 제대로 왕복했다는 뜻이다(R-03).
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
class BacktestRetryIT extends PostgresTestSupport {

    @Autowired private BacktestService backtestService;
    @Autowired private BacktestRetryService retryService;
    @Autowired private BacktestRequestRepository requestRepository;
    @Autowired private BacktestRunRepository runRepository;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private TransactionTemplate transactionTemplate;

    private long newUser(String tag) {
        String suffix = tag + (System.nanoTime() % 1_000_000_000L);
        String email = "retry-" + suffix + "@example.com";
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

    /** 다른 IT 와 겹치지 않는 기간을 쓴다 — 본문이 같으면 저쪽 실행에 합류해 버린다. */
    private static CreateBacktestCommand request(long requesterId) {
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
                LocalDate.of(2024, 5, 2),
                LocalDate.of(2024, 8, 30),
                new BigDecimal("10000000.00"),
                null,
                "SPLIT-1",
                null,
                Scenario.FULL_PERIOD,
                null);
    }

    private String dedupKeyOf(Long runId) {
        return jdbcTemplate.queryForObject(
                "SELECT dedup_key FROM backtest_run WHERE id = ?", String.class, runId);
    }

    private Long runIdOf(String backtestId) {
        return requestRepository.findByBacktestId(backtestId).orElseThrow().getRunId();
    }

    /** 워커 없이 실행을 실패로 끝낸다. */
    private void failRun(Long runId) {
        transactionTemplate.executeWithoutResult(
                status -> {
                    BacktestRun run = runRepository.findById(runId).orElseThrow();
                    BacktestRun failed = run.markFailed("SYSTEM_FAILURE", "worker gone");
                    runRepository.update(
                            failed.withLifecycle(failed.getLifecycle().finished(Instant.now())));
                });
    }

    @Test
    @DisplayName("실패한 분석을 재시도하면 같은 조건의 새 실행이 생기고 원본을 가리킨다")
    void retryRecreatesTheSameConditions() {
        long user = newUser("a");
        BacktestJobResult original = backtestService.create(request(user));
        Long originalRunId = runIdOf(original.backtestId());
        failRun(originalRunId);

        BacktestJobResult retried = retryService.retry(original.backtestId(), user);

        assertThat(retried.retryOf()).isEqualTo(original.backtestId());
        assertThat(retried.backtestId()).isNotEqualTo(original.backtestId());
        assertThat(retried.resultSource()).isEqualTo(ResultSource.NEW);

        Long retriedRunId = runIdOf(retried.backtestId());
        assertThat(retriedRunId).isNotEqualTo(originalRunId);
        // 사본이 JSONB 를 왕복해도 결과를 바꾸는 값은 하나도 새지 않았다.
        assertThat(dedupKeyOf(retriedRunId)).isEqualTo(dedupKeyOf(originalRunId));
        // 실효 기간도 같은 값으로 다시 확정된다(워밍업이 그대로 왕복했다는 뜻이다).
        assertThat(retried.effectiveStart()).isEqualTo(original.effectiveStart());
        assertThat(retried.effectiveEnd()).isEqualTo(original.effectiveEnd());

        BacktestRequest saved =
                requestRepository.findByBacktestId(retried.backtestId()).orElseThrow();
        assertThat(saved.getRetryOfRequestId())
                .isEqualTo(
                        requestRepository
                                .findByBacktestId(original.backtestId())
                                .orElseThrow()
                                .getId());
    }

    @Test
    @DisplayName("아직 돌고 있는 분석은 409 NOT_RETRYABLE 이다")
    void refusesToRetryARunningRequest() {
        long user = newUser("b");
        BacktestJobResult accepted = backtestService.create(request(user));

        assertThatThrownBy(() -> retryService.retry(accepted.backtestId(), user))
                .isInstanceOf(BusinessException.class)
                .extracting(exc -> ((BusinessException) exc).getErrorCode())
                .isEqualTo(ErrorCode.NOT_RETRYABLE);
        assertThat(runRepository.findById(runIdOf(accepted.backtestId())).orElseThrow().getStatus())
                .isEqualTo(RunStatus.QUEUED);
    }
}
