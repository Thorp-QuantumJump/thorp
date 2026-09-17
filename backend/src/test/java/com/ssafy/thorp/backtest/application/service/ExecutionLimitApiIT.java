package com.ssafy.thorp.backtest.application.service;

import static com.ssafy.thorp.support.Requesters.as;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ssafy.thorp.backtest.application.command.CreateBacktestCommand;
import com.ssafy.thorp.backtest.application.port.message.Scenario;
import com.ssafy.thorp.backtest.application.repository.BacktestRequestRepository;
import com.ssafy.thorp.backtest.application.repository.BacktestRunRepository;
import com.ssafy.thorp.backtest.application.result.BacktestJobResult;
import com.ssafy.thorp.backtest.domain.model.BacktestRequest;
import com.ssafy.thorp.backtest.domain.model.BacktestRun;
import com.ssafy.thorp.backtest.domain.model.CancelReason;
import com.ssafy.thorp.backtest.domain.model.ExecutionType;
import com.ssafy.thorp.backtest.domain.model.RunStatus;
import com.ssafy.thorp.common.exception.BusinessException;
import com.ssafy.thorp.common.exception.ErrorCode;
import com.ssafy.thorp.support.PostgresTestSupport;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 실행 한도 장치(spec 004 T014, FR-006, R-19). 실제 DB 로 접수 거절과 강제 종결을 고정한다.
 *
 * <p>한도를 작게 눌러(사용자 2·전체 3) 시나리오를 짧게 만든다 — <b>값이 아니라 장치를 보는 테스트</b>다. 실제 값은 PD-06 이 실측 뒤 정한다.
 *
 * <p><b>전체 한도는 여기서 보지 않는다.</b> 스위트가 PostgreSQL 컨테이너를 공유하므로 다른 IT 가 남긴 대기 실행까지 함께 세어져, 몇 건을 넣어야 넘는지가
 * 실행 순서에 따라 달라진다. 그 분기는 {@code ExecutionLimitPolicyTest} 가 목으로 고정한다 — 여기서는 넉넉히 두어 사용자별 한도만 보이게 한다.
 *
 * <p>스케줄러는 꺼 두고 {@code sweep()} 을 직접 부른다. 주기를 기다리면 테스트가 시간에 의존하고, 우리가 보려는 것은 "주기가 도는가"가 아니라 "무엇을
 * 거두는가"다.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.rabbitmq.host=localhost",
            "spring.rabbitmq.port=1",
            "spring.rabbitmq.connection-timeout=1s",
            "spring.rabbitmq.listener.simple.auto-startup=false",
            "thorp.backtest.republish.enabled=false",
            "thorp.backtest.liveness.interval=PT1H",
            "thorp.backtest.limits.max-queued-per-user=2",
            "thorp.backtest.limits.max-queued-total=1000",
            // 공유 컨테이너에 다른 IT 가 남긴 비로그인 대기 실행이 섞인다. 몫의 판정은 ExecutionLimitPolicyTest 가, 셈은 증가분으로 본다.
            "thorp.backtest.limits.max-queued-anonymous=1000",
            "thorp.backtest.limits.max-run-seconds=1800",
            "thorp.backtest.limits.stale-run-seconds=300",
            "thorp.backtest.limits.cancel-grace-seconds=1",
            "thorp.backtest.limits.provisional=true",
            "spring.mail.username=test@example.com",
            "spring.mail.password=test"
        })
@AutoConfigureMockMvc
class ExecutionLimitApiIT extends PostgresTestSupport {

    @Autowired private MockMvc mockMvc;
    @Autowired private BacktestService backtestService;
    @Autowired private BacktestRunRepository runRepository;
    @Autowired private BacktestRequestRepository requestRepository;
    @Autowired private RunLivenessSweeper sweeper;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private TransactionTemplate transactionTemplate;

    private long newUser(String tag) {
        String suffix = tag + (System.nanoTime() % 1_000_000_000L);
        String email = "limit-" + suffix + "@example.com";
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
    private static CreateBacktestCommand request(Long requesterId, int day, ExecutionType type) {
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
                LocalDate.of(2024, 2, day),
                LocalDate.of(2024, 11, 29),
                new BigDecimal("10000000.00"),
                null,
                "SPLIT-1",
                null,
                Scenario.FULL_PERIOD,
                null);
    }

    private static ErrorCode errorCodeOf(Throwable thrown) {
        return ((BusinessException) thrown).getErrorCode();
    }

    @Test
    @DisplayName("사용자별 대기 상한을 넘으면 429 EXECUTION_LIMIT 이고 본문에 판단 근거와 임시값 표시가 실린다")
    void rejectsBeyondThePerUserQueueLimit() {
        long user = newUser("a");
        backtestService.create(request(user, 1, ExecutionType.SINGLE));
        backtestService.create(request(user, 2, ExecutionType.SINGLE));

        Throwable thrown =
                catchThrowable(
                        () -> backtestService.create(request(user, 5, ExecutionType.SINGLE)));

        assertThat(errorCodeOf(thrown)).isEqualTo(ErrorCode.EXECUTION_LIMIT);
        assertThat(((BusinessException) thrown).getDetails())
                .containsEntry("reason", "PER_USER")
                .containsEntry("queued", 2L)
                .containsEntry("provisional", true)
                .containsKey("retryAfterSeconds");
    }

    @Test
    @DisplayName("비로그인 요청은 사용자별 한도에 걸리지 않는다 — 요청자를 셀 열쇠가 없어 비로그인 몫과 전체 한도로 센다(spec 005 T012)")
    void anonymousRequestsAreNotCountedAgainstAPerUserLimit() {
        // 사용자별 상한(2)을 넘는 세 건이다. 시작일은 다른 테스트와 겹치지 않게 20일대를 쓴다.
        for (int day = 20; day <= 22; day++) {
            CreateBacktestCommand anonymous = request(0L, day, ExecutionType.SINGLE);
            anonymous =
                    new CreateBacktestCommand(
                            null,
                            anonymous.executionType(),
                            anonymous.mappingVersion(),
                            anonymous.items(),
                            anonymous.strategyId(),
                            anonymous.rulesVersion(),
                            anonymous.registryVersion(),
                            anonymous.parameters(),
                            anonymous.start(),
                            anonymous.end(),
                            anonymous.initialCash(),
                            anonymous.costProfileVersion(),
                            anonymous.evaluationSplitVersion(),
                            anonymous.warmupTradingDays(),
                            anonymous.scenario(),
                            anonymous.scenarioParams());

            assertThat(backtestService.create(anonymous).publicToken()).matches("^[0-9a-f]{32}$");
        }
    }

    /**
     * 비로그인 몫의 셈이 무엇을 세는지 실제 DB 로 고정한다. 컨테이너를 공유해 다른 IT 가 남긴 대기 실행이 섞이므로 절대값이 아니라 <b>증가분</b>을 본다(전체
     * 한도를 여기서 보지 않는 것과 같은 이유).
     */
    @Test
    @DisplayName("비로그인 몫은 비로그인 요청이 새로 만든 대기 실행만 센다 — 로그인 요청과 비로그인 합류는 세지 않는다(spec 005 B01)")
    void anonymousShareCountsOnlyRunsCreatedByAnonymousRequests() {
        long before = runRepository.countQueuedSingleAnonymous();

        backtestService.create(anonymousRequest(24));
        assertThat(runRepository.countQueuedSingleAnonymous()).isEqualTo(before + 1);

        // 로그인 사용자가 만든 실행은 세지 않는다.
        long user = newUser("anon-share");
        backtestService.create(request(user, 25, ExecutionType.SINGLE));
        assertThat(runRepository.countQueuedSingleAnonymous()).isEqualTo(before + 1);

        // 비로그인 요청이 그 실행에 합류해도 큐는 늘지 않으므로 세지 않는다.
        assertThat(backtestService.create(anonymousRequest(25)).resultSource())
                .isEqualTo(com.ssafy.thorp.backtest.domain.model.ResultSource.JOINED);
        assertThat(runRepository.countQueuedSingleAnonymous()).isEqualTo(before + 1);

        // 같은 조건으로 다시 낸 비로그인 요청도 합류라 늘지 않는다.
        backtestService.create(anonymousRequest(24));
        assertThat(runRepository.countQueuedSingleAnonymous()).isEqualTo(before + 1);
    }

    private static CreateBacktestCommand anonymousRequest(int day) {
        CreateBacktestCommand base = request(0L, day, ExecutionType.SINGLE);
        return new CreateBacktestCommand(
                null,
                base.executionType(),
                base.mappingVersion(),
                base.items(),
                base.strategyId(),
                base.rulesVersion(),
                base.registryVersion(),
                base.parameters(),
                base.start(),
                base.end(),
                base.initialCash(),
                base.costProfileVersion(),
                base.evaluationSplitVersion(),
                base.warmupTradingDays(),
                base.scenario(),
                base.scenarioParams());
    }

    @Test
    @DisplayName("탐색 묶음은 사용자별 한도에 걸리지 않는다 — 적재량은 007 의 발행 창이 통제한다")
    void searchBundlesAreNotCountedAgainstThePerUserLimit() {
        long user = newUser("b");
        backtestService.create(request(user, 6, ExecutionType.SINGLE));
        backtestService.create(request(user, 7, ExecutionType.SINGLE));

        // 단건이었다면 세 번째에서 막혔을 자리다.
        assertThat(backtestService.create(request(user, 8, ExecutionType.SEARCH_BUNDLE)))
                .isNotNull();
    }

    @Test
    @DisplayName("동시에 들어와도 상한을 넘겨 통과하지 않는다 — 세는 구간이 잠긴다")
    void concurrentIntakeNeverExceedsTheLimit() throws Exception {
        long user = newUser("e");
        // 상한 2 중 하나를 미리 채운다. 남은 한 자리를 두 스레드가 겨루게 해 커넥션을 아낀다 —
        // PostgresTestSupport 는 Hikari 상한이 2라 스레드를 늘리면 풀에서 먼저 막힌다.
        backtestService.create(request(user, 13, ExecutionType.SINGLE));
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        int accepted = 0;
        int rejected = 0;
        try {
            List<Future<Object>> futures =
                    List.of(pool.submit(admit(go, user, 14)), pool.submit(admit(go, user, 15)));
            go.countDown();
            for (Future<Object> future : futures) {
                try {
                    future.get(30, TimeUnit.SECONDS);
                    accepted++;
                } catch (ExecutionException exc) {
                    assertThat(errorCodeOf(exc.getCause())).isEqualTo(ErrorCode.EXECUTION_LIMIT);
                    rejected++;
                }
            }
        } finally {
            pool.shutdownNow();
        }

        // 잠그지 않으면 둘 다 "지금 1건"을 읽고 함께 통과한다.
        assertThat(accepted).isEqualTo(1);
        assertThat(rejected).isEqualTo(1);
    }

    private Callable<Object> admit(CountDownLatch go, long user, int day) {
        return () -> {
            go.await();
            return backtestService.create(request(user, day, ExecutionType.SINGLE));
        };
    }

    @Test
    @DisplayName("한도 거절이 호출자의 트랜잭션을 되돌리지 않는다 — 이미 끝난 남의 일이 함께 사라지면 안 된다")
    void rejectionDoesNotPoisonTheCallerTransaction() {
        long user = newUser("g");
        // 상한 2를 채워 다음 접수가 반드시 429 가 되게 한다.
        backtestService.create(request(user, 17, ExecutionType.SINGLE));
        backtestService.create(request(user, 18, ExecutionType.SINGLE));

        // 007 의 candidateFinished 와 같은 모양: 한 트랜잭션에서 남의 일을 커밋하고, 그 안에서
        // 다음 접수를 시도한다. 기본 롤백 규칙이면 이 트랜잭션은 rollback-only 가 되어
        // 아래 INSERT 까지 사라진다 — 접수를 거절하려다 이미 끝난 후보의 정산을 지우는 셈이다.
        // phone_number 가 varchar(20) 이라 짧게 만든다.
        String marker = "lim" + (System.nanoTime() % 1_000_000_000L);
        transactionTemplate.executeWithoutResult(
                status -> {
                    jdbcTemplate.update(
                            "INSERT INTO users (email, password_hash, nickname, phone_number,"
                                    + " status, paper_ranking_enabled, live_ranking_enabled,"
                                    + " ranking_display_nickname, created_at, updated_at)"
                                    + " VALUES (?, 'x', ?, ?, 'ACTIVE', false, false, false, NOW(),"
                                    + " NOW())",
                            marker + "@example.com",
                            marker,
                            marker);
                    assertThat(
                                    errorCodeOf(
                                            catchThrowable(
                                                    () ->
                                                            backtestService.create(
                                                                    request(
                                                                            user,
                                                                            19,
                                                                            ExecutionType
                                                                                    .SINGLE)))))
                            .isEqualTo(ErrorCode.EXECUTION_LIMIT);
                });

        assertThat(
                        jdbcTemplate.queryForObject(
                                "SELECT count(*) FROM users WHERE email = ?",
                                Integer.class,
                                marker + "@example.com"))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("팀 결정 대기 전략의 거절은 422 다 — 안쪽 프록시가 트랜잭션을 rollback-only 로 표시해 500 이 되면 안 된다")
    void pendingRulesRejectionIsA422NotASilentRollback() {
        // quickstart S9(T013)에서 발견: create 는 noRollbackFor 인데
        // StrategyRulesService.requireRegistered 의
        // 프록시가 기본 규칙으로 바깥 트랜잭션을 rollback-only 로 표시해 커밋 시점에 UnexpectedRollbackException.
        long user = newUser("p");
        CreateBacktestCommand pending =
                new CreateBacktestCommand(
                        user,
                        ExecutionType.SINGLE,
                        "SECTOR-ETF-2026-09",
                        List.of(
                                new CreateBacktestCommand.AllocationItem(
                                        "SEMICONDUCTOR", "091160.XKRX", null),
                                new CreateBacktestCommand.AllocationItem(
                                        "BATTERY", "305720.XKRX", null)),
                        "SECTOR_MOMENTUM",
                        "PENDING",
                        // registryVersion 을 싣지 않는다 — 이 픽스처의 parameters 는 워커가 읽는 이름이고
                        // 003 등록표의 축 이름이 아니다. 값을 실으면 접수가 등록표 판정을 걸어 409 다.
                        null,
                        Map.of("rebalance", "MONTH_END"),
                        LocalDate.of(2024, 2, 1),
                        LocalDate.of(2024, 11, 29),
                        new BigDecimal("10000000.00"),
                        null,
                        "SPLIT-1",
                        null,
                        Scenario.FULL_PERIOD,
                        null);

        Throwable thrown = catchThrowable(() -> backtestService.create(pending));

        assertThat(thrown).isInstanceOf(BusinessException.class);
        assertThat(errorCodeOf(thrown)).isEqualTo(ErrorCode.RULES_NOT_REGISTERED);
    }

    @Test
    @DisplayName("취소에 응답하지 않으면 유예 뒤 FAILED 로 강제 종결하고, 상세는 500 이 아니라 그 사유를 보여 준다")
    void forceFinishesAnUnansweredCancel() throws Exception {
        long user = newUser("f");
        String backtestId =
                backtestService.create(request(user, 16, ExecutionType.SINGLE)).backtestId();
        Long runId =
                jdbcTemplate.queryForObject(
                        "SELECT run_id FROM backtest_request WHERE backtest_id = ?",
                        Long.class,
                        backtestId);
        // 워커 없이 CANCEL 만 보낸 상태를 만든다(유예는 1초로 눌러 두었다).
        transactionTemplate.executeWithoutResult(
                status ->
                        runRepository.recordCancelRequested(
                                runId, Instant.now().minusSeconds(60), CancelReason.USER));

        sweeper.sweep();

        assertThat(runRepository.findById(runId).orElseThrow().getStatus())
                .isEqualTo(RunStatus.FAILED);
        // 결과 행은 만들지 않는다 — 워커가 계산을 마치지 못했다.
        assertThat(
                        jdbcTemplate.queryForObject(
                                "SELECT count(*) FROM backtest_result WHERE run_id = ?",
                                Integer.class,
                                runId))
                .isZero();
        mockMvc.perform(get("/backtests/{id}", backtestId).with(as(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("FAILED"))
                .andExpect(jsonPath("$.data.failure.code").value("SYSTEM_FAILURE"))
                .andExpect(jsonPath("$.data.summary").doesNotExist());
    }

    @Test
    @DisplayName(
            "재시도가 한도에 걸리면 소유자 경로·공개 경로 모두 429 이고 거절 기록이 남는다 — 바깥 트랜잭션이 기록을 되돌리지 않는다(spec 005 B02)")
    void retryRejectedByLimitKeepsTheRecordOnBothRoutes() throws Exception {
        long user = newUser("r");
        // 실패한 요청을 먼저 만든다. 상한을 채운 뒤에 만들면 그 접수부터 429 다.
        BacktestJobResult failed = backtestService.create(request(user, 22, ExecutionType.SINGLE));
        transactionTemplate.executeWithoutResult(
                status -> {
                    BacktestRequest found =
                            requestRepository.findByBacktestId(failed.backtestId()).orElseThrow();
                    BacktestRun run = runRepository.findById(found.getRunId()).orElseThrow();
                    runRepository.update(run.markFailed("DATA_INSUFFICIENT", "no data"));
                });
        backtestService.create(request(user, 20, ExecutionType.SINGLE));
        backtestService.create(request(user, 21, ExecutionType.SINGLE));

        String hits = "SELECT count(*) FROM backtest_limit_hit WHERE requester_id = ?";
        int before = jdbcTemplate.queryForObject(hits, Integer.class, user);

        mockMvc.perform(
                        post("/backtests/" + failed.backtestId() + "/retry")
                                .with(csrf())
                                .with(as(user)))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error.code").value("EXECUTION_LIMIT"));
        assertThat(jdbcTemplate.queryForObject(hits, Integer.class, user)).isEqualTo(before + 1);

        String body =
                mockMvc.perform(
                                post("/results/" + failed.publicToken() + "/retry")
                                        .with(csrf())
                                        .with(as(user)))
                        .andExpect(status().isTooManyRequests())
                        .andExpect(jsonPath("$.error.code").value("EXECUTION_LIMIT"))
                        .andExpect(jsonPath("$.error.details.retryAfterSeconds").exists())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        assertThat(body).doesNotContain("BT-");
        assertThat(jdbcTemplate.queryForObject(hits, Integer.class, user)).isEqualTo(before + 2);
    }

    @Test
    @DisplayName(
            "비로그인 요청은 단건이 아니면 접수 전에 400 이다 — 비단건은 한도 밖이라 그대로 받으면 대기열을 무제한으로 채운다 (spec 005 T089, 원칙 IX)")
    void anonymousNonSingleIntakeIsRejectedBeforeQueueing() throws Exception {
        String body =
                java.nio.file.Files.readString(
                                java.nio.file.Path.of(
                                        "..",
                                        "docs",
                                        "specs",
                                        "backtest-api",
                                        "examples",
                                        "backtest-request.json"))
                        .replace("\"SINGLE\"", "\"SEARCH_BUNDLE\"");
        String runs = "SELECT count(*) FROM backtest_run";
        long before = jdbcTemplate.queryForObject(runs, Long.class);

        mockMvc.perform(
                        post("/backtests")
                                .with(csrf())
                                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                                .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_INPUT"));

        assertThat(jdbcTemplate.queryForObject(runs, Long.class)).isEqualTo(before);
    }

    @Test
    @DisplayName("비로그인 사용자가 묶음 요청의 공개 주소로 다시 시도하면 400 이고 새 요청이 생기지 않는다 (spec 005 T089)")
    void anonymousPublicRetryOfNonSingleIsRejected() throws Exception {
        long owner = newUser("b");
        BacktestJobResult bundle =
                backtestService.create(request(owner, 26, ExecutionType.SEARCH_BUNDLE));
        transactionTemplate.executeWithoutResult(
                status -> {
                    BacktestRequest found =
                            requestRepository.findByBacktestId(bundle.backtestId()).orElseThrow();
                    BacktestRun run = runRepository.findById(found.getRunId()).orElseThrow();
                    runRepository.update(run.markFailed("DATA_INSUFFICIENT", "no data"));
                });
        String requests = "SELECT count(*) FROM backtest_request";
        long before = jdbcTemplate.queryForObject(requests, Long.class);

        mockMvc.perform(post("/results/" + bundle.publicToken() + "/retry").with(csrf()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_INPUT"));

        assertThat(jdbcTemplate.queryForObject(requests, Long.class)).isEqualTo(before);
    }
}
