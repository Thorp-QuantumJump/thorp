package com.ssafy.thorp.backtest.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.ssafy.thorp.backtest.application.command.CreateBacktestCommand;
import com.ssafy.thorp.backtest.application.port.message.Scenario;
import com.ssafy.thorp.backtest.domain.model.ExecutionType;
import com.ssafy.thorp.common.exception.BusinessException;
import com.ssafy.thorp.common.exception.ErrorCode;
import com.ssafy.thorp.support.IntegrationTestSupport;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

/**
 * 단건 큐 전체가 찼을 때 무엇을 막고 무엇을 흘려보내는가(spec 004 T014·T015, R-19·R-20).
 *
 * <p><b>상한을 1로 둔다.</b> 스위트가 PostgreSQL 컨테이너를 공유해 다른 IT 가 남긴 대기 실행이 함께 세어지는데, 그 잔여물은 수를 <b>늘리기만</b>
 * 하므로 상한이 1이면 단건 한 건을 넣은 뒤 "이미 찼다"가 실행 순서와 무관하게 참이다. {@code ExecutionLimitApiIT} 는 반대로 전체 한도가 걸리지
 * 않게 넉넉히 두고 사용자별 한도만 본다.
 *
 * <p>여기서 고정하는 것은 값이 아니라 <b>가르는 기준</b>이다 — 한도는 단건 큐의 것이고, 다른 큐로 가는 탐색·경제환경 파생 실행은 단건 큐가 아무리 차 있어도
 * 접수된다.
 */
// 전체 한도를 1로 좁히므로 기반 컨텍스트와 따로 뜬다.
@TestPropertySource(properties = "thorp.backtest.limits.max-queued-total=1")
class ExecutionLimitFullQueueIT extends IntegrationTestSupport {

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
                // registryVersion 을 싣지 않는다 — 이 픽스처의 parameters 는 워커가 읽는 이름이고
                // 003 등록표의 축 이름이 아니다. 값을 실으면 접수가 등록표 판정을 걸어 409 다.
                null,
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
    @DisplayName("단건 큐가 차면 단건은 막고, 다른 큐로 가는 탐색·경제환경 실행은 세지도 막지도 않는다")
    void rejectsSingleButNeverTouchesBulkRuns() {
        long user = newUser("a");
        // 첫 단건은 잔여물이 있으면 막히고 없으면 통과한다 — 어느 쪽이든 이 뒤로 단건 큐는 상한 1에 닿아 있다.
        catchThrowable(() -> backtestService.create(request(user, 3, ExecutionType.SINGLE)));

        // 사람이 기다리는 단건은 여기서 막는다 — 단건 큐 보호의 요점이다.
        Throwable rejected =
                catchThrowable(
                        () -> backtestService.create(request(user, 4, ExecutionType.SINGLE)));
        assertThat(((BusinessException) rejected).getErrorCode())
                .isEqualTo(ErrorCode.EXECUTION_LIMIT);
        assertThat(((BusinessException) rejected).getDetails()).containsEntry("reason", "TOTAL");

        // 벌크 큐로 가는 실행은 단건 큐 상태와 무관하다(R-20). 한 큐였을 때는 여기서 거절하면 007 묶음이
        // 영구 정지했고, 통과시키면 배치가 큐를 채우는 동안 단건만 429 를 받았다.
        assertThat(backtestService.create(request(user, 5, ExecutionType.SEARCH_BUNDLE)))
                .isNotNull();
        assertThat(backtestService.create(request(user, 6, ExecutionType.REGIME_SECTOR)))
                .isNotNull();
        // 벌크가 쌓여도 단건 큐의 셈은 그대로다 — 단건이 또 막히는 이유는 단건끼리의 경쟁뿐이다.
        Throwable stillTotal =
                catchThrowable(
                        () -> backtestService.create(request(user, 7, ExecutionType.SINGLE)));
        assertThat(((BusinessException) stillTotal).getDetails())
                .containsEntry("reason", "TOTAL")
                .containsEntry("limit", 1);
    }
}
