package com.ssafy.thorp.backtest.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.ssafy.thorp.backtest.application.command.CreateBacktestCommand;
import com.ssafy.thorp.backtest.application.port.message.Scenario;
import com.ssafy.thorp.backtest.domain.model.ExecutionType;
import com.ssafy.thorp.common.exception.BusinessException;
import com.ssafy.thorp.common.exception.ErrorCode;
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

/**
 * 서버 전체 대기열이 찼을 때 무엇을 막고 무엇을 흘려보내는가(spec 004 T014, R-19).
 *
 * <p><b>상한을 1로 둔다.</b> 스위트가 PostgreSQL 컨테이너를 공유해 다른 IT 가 남긴 대기 실행이 함께 세어지는데, 그 잔여물은 수를 <b>늘리기만</b>
 * 하므로 상한이 1이면 "이미 찼다"가 실행 순서와 무관하게 참이다. {@code ExecutionLimitApiIT} 는 반대로 전체 한도가 걸리지 않게 넉넉히 두고 사용자별
 * 한도만 본다.
 *
 * <p>여기서 고정하는 것은 값이 아니라 <b>가르는 기준</b>이다 — 사람이 기다리는 단건은 막고, 돌던 묶음의 다음 후보는 흘려보낸다.
 */
@SpringBootTest(
        properties = {
            "spring.rabbitmq.host=localhost",
            "spring.rabbitmq.port=1",
            "spring.rabbitmq.connection-timeout=1s",
            "spring.rabbitmq.listener.simple.auto-startup=false",
            "thorp.backtest.republish.enabled=false",
            "thorp.backtest.liveness.interval=PT1H",
            "thorp.backtest.limits.max-queued-per-user=100",
            "thorp.backtest.limits.max-queued-total=1",
            "thorp.backtest.limits.max-run-seconds=1800",
            "thorp.backtest.limits.stale-run-seconds=300",
            "thorp.backtest.limits.cancel-grace-seconds=120",
            "thorp.backtest.limits.provisional=true",
            "spring.mail.username=test@example.com",
            "spring.mail.password=test"
        })
class ExecutionLimitFullQueueIT extends PostgresTestSupport {

    @Autowired private BacktestService backtestService;
    @Autowired private JdbcTemplate jdbcTemplate;

    private long newUser(String tag) {
        String suffix = tag + (System.nanoTime() % 1_000_000_000L);
        String email = "full-" + suffix + "@example.com";
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

    /** 시작일이 다르면 동일 버전 키가 달라 합류하지 않는다 — 매번 새 실행이 큐에 쌓인다. */
    private static CreateBacktestCommand request(long requesterId, int day, ExecutionType type) {
        return new CreateBacktestCommand(
                requesterId,
                type,
                "SECTOR-ETF-2026-09",
                List.of(
                        new CreateBacktestCommand.AllocationItem(
                                "SEMICONDUCTOR", "091160.XKRX", new BigDecimal("0.4000")),
                        new CreateBacktestCommand.AllocationItem(
                                "BATTERY", "305720.XKRX", new BigDecimal("0.6000"))),
                "FIXED_WEIGHT",
                "1",
                "PARAM-2026-09",
                Map.of("rebalance", "MONTH_END"),
                LocalDate.of(2024, 6, day),
                LocalDate.of(2024, 11, 29),
                new BigDecimal("10000000.00"),
                null,
                "SPLIT-1",
                null,
                Scenario.FULL_PERIOD,
                null);
    }

    @Test
    @DisplayName("전체 대기열이 차면 단건은 막고 탐색 후보는 흘려보낸다 — 끊으면 그 묶음이 다시 깨어나지 못한다")
    void rejectsSingleButKeepsSearchBundlesFlowing() {
        long user = newUser("a");
        // 상한 1을 확실히 넘긴다. 묶음은 한도 위에서도 접수된다.
        backtestService.create(request(user, 3, ExecutionType.SEARCH_BUNDLE));

        // 사람이 기다리는 단건은 여기서 막는다 — 서버 보호의 요점이다.
        Throwable rejected =
                catchThrowable(
                        () -> backtestService.create(request(user, 4, ExecutionType.SINGLE)));
        assertThat(((BusinessException) rejected).getErrorCode())
                .isEqualTo(ErrorCode.EXECUTION_LIMIT);
        assertThat(((BusinessException) rejected).getDetails()).containsEntry("reason", "TOTAL");

        // 돌던 묶음의 다음 후보는 통과해야 한다. 007 은 완료 이벤트로만 다음 후보를 내보내므로,
        // 마지막 진행 후보가 끝난 순간 여기서 거절하면 남은 후보가 PENDING 인 채로 깨울 신호가 없다.
        assertThat(backtestService.create(request(user, 5, ExecutionType.SEARCH_BUNDLE)))
                .isNotNull();
        assertThat(backtestService.create(request(user, 6, ExecutionType.REGIME_SECTOR)))
                .isNotNull();
    }
}
