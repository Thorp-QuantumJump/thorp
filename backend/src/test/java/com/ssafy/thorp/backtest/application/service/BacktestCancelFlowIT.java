package com.ssafy.thorp.backtest.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ssafy.thorp.backtest.application.command.CreateBacktestCommand;
import com.ssafy.thorp.backtest.application.port.BacktestControlPublisher;
import com.ssafy.thorp.backtest.application.repository.BacktestRequestRepository;
import com.ssafy.thorp.backtest.application.repository.BacktestRunRepository;
import com.ssafy.thorp.backtest.domain.model.BacktestRequest;
import com.ssafy.thorp.backtest.domain.model.BacktestRun;
import com.ssafy.thorp.backtest.domain.model.ExecutionType;
import com.ssafy.thorp.backtest.domain.model.PositionSizingMode;
import com.ssafy.thorp.backtest.domain.model.ResultSource;
import com.ssafy.thorp.backtest.domain.model.RunStatus;
import com.ssafy.thorp.backtest.domain.vo.BacktestPeriod;
import com.ssafy.thorp.backtest.domain.vo.InstrumentId;
import com.ssafy.thorp.backtest.infrastructure.messaging.MessageFixtures;
import com.ssafy.thorp.backtest.presentation.support.CurrentRequester;
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
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 취소 흐름(spec 004 R-04, tasks T011 ⓐ~ⓔ + 대기 중 취소). 실제 DB 에 요청·실행·결과를 두고 취소 API → 이벤트 처리 → 조회 API 를
 * 이어 본다.
 *
 * <p>브로커는 띄우지 않는다. CANCEL 발행은 목으로 받아 "보냈는지"만 본다 — 워커가 멈춘 뒤 보내는 FINISHED(CANCELLED)는 계약 픽스처 ({@code
 * lastTradingDate=2025-03-25})로 넣는다. PROGRESS 픽스처의 마지막 거래일은 2025-01-28 이다.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.rabbitmq.host=localhost",
            "spring.rabbitmq.port=1",
            "spring.rabbitmq.connection-timeout=1s",
            "spring.rabbitmq.listener.simple.auto-startup=false",
            "thorp.backtest.republish.enabled=false",
            "thorp.auth.trust-user-id-header=true",
            "spring.mail.username=test@example.com",
            "spring.mail.password=test"
        })
@AutoConfigureMockMvc
class BacktestCancelFlowIT extends PostgresTestSupport {

    private static final LocalDate PROGRESS_DATE = LocalDate.parse("2025-01-28");
    private static final LocalDate STOPPED_DATE = LocalDate.parse("2025-03-25");

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private BacktestEventService events;
    @Autowired private BacktestCancelService cancelService;
    @Autowired private BacktestService backtestService;

    @Autowired
    private org.springframework.transaction.PlatformTransactionManager transactionManager;

    @Autowired private BacktestRunRepository runRepository;
    @Autowired private BacktestRequestRepository requestRepository;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private TransactionTemplate transactionTemplate;
    @MockitoBean private BacktestControlPublisher controlPublisher;

    // ---------------------------------------------------------------- fixtures

    private long newUser() {
        String suffix = Long.toString(System.nanoTime() % 1_000_000_000L);
        String email = "cancel-" + suffix + "@example.com";
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

    private BacktestRun newRun() {
        return transactionTemplate.execute(
                status ->
                        runRepository.save(
                                BacktestRun.create(
                                        "MA_CROSSOVER",
                                        "1.0.0",
                                        Map.of("shortWindow", 20),
                                        List.of(
                                                new InstrumentId("005930.XKRX"),
                                                new InstrumentId("000660.XKRX")),
                                        new BacktestPeriod(
                                                LocalDate.of(2025, 1, 1),
                                                LocalDate.of(2025, 6, 30)),
                                        new BigDecimal("10000000"),
                                        PositionSizingMode.STRATEGY_DEFINED,
                                        new BigDecimal("0.50"),
                                        new InstrumentId("069500.XKRX"),
                                        42)));
    }

    /** 실행에 붙은 요청 하나. 두 번째부터는 합류(JOINED)다. */
    private String requestFor(BacktestRun run, long requesterId, ResultSource source) {
        return transactionTemplate.execute(
                status ->
                        requestRepository
                                .save(
                                        BacktestRequest.create(
                                                run.getId(),
                                                requesterId,
                                                ExecutionType.SINGLE,
                                                source,
                                                null))
                                .getBacktestId()
                                .value());
    }

    private void started(BacktestRun run) {
        events.handleStarted(
                MessageFixtures.parse(
                        MessageFixtures.withRunId(
                                MessageFixtures.STARTED, run.getBacktestId().value()),
                        MessageFixtures.STARTED_EVENT));
    }

    private void progressed(BacktestRun run) {
        events.handleProgress(
                MessageFixtures.parse(
                        MessageFixtures.withRunId(
                                MessageFixtures.PROGRESS, run.getBacktestId().value()),
                        MessageFixtures.PROGRESS_EVENT));
    }

    private void finished(BacktestRun run, String file) {
        events.handleFinished(
                MessageFixtures.parse(
                        MessageFixtures.withRunId(file, run.getBacktestId().value()),
                        MessageFixtures.FINISHED_EVENT));
    }

    private BacktestRun runningWithProgress() {
        BacktestRun run = newRun();
        started(run);
        progressed(run);
        return run;
    }

    /** 접수 경로를 실제로 태워야 동일 버전 키가 붙어 합류 판정이 돈다. */
    private static CreateBacktestCommand intake(long requesterId) {
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
                "PARAM-2026-09",
                Map.of("rebalance", "MONTH_END"),
                LocalDate.of(2024, 1, 2),
                LocalDate.of(2024, 12, 31),
                new BigDecimal("10000000.00"),
                null,
                "SPLIT-1",
                null,
                com.ssafy.thorp.backtest.application.port.message.Scenario.FULL_PERIOD,
                null);
    }

    /** 접수가 동일 버전 조회에 쓰는 키. 실행 행에 저장된 값을 그대로 읽는다. */
    private String dedupKeyOf(Long runId) {
        return jdbcTemplate.queryForObject(
                "SELECT dedup_key FROM backtest_run WHERE id = ?", String.class, runId);
    }

    // ---------------------------------------------------------------- http helpers

    private JsonNode cancel(String backtestId, long user) throws Exception {
        String body =
                mockMvc.perform(
                                post("/backtests/{id}/cancel", backtestId)
                                        .header(CurrentRequester.DEV_HEADER, user))
                        .andExpect(status().isAccepted())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        return objectMapper.readTree(body).get("data");
    }

    private JsonNode detail(String backtestId, long user) throws Exception {
        String body =
                mockMvc.perform(
                                get("/backtests/{id}", backtestId)
                                        .header(CurrentRequester.DEV_HEADER, user))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        return objectMapper.readTree(body).get("data");
    }

    private JsonNode timeseries(String backtestId, long user) throws Exception {
        String body =
                mockMvc.perform(
                                get("/backtests/{id}/timeseries", backtestId)
                                        .header(CurrentRequester.DEV_HEADER, user))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        return objectMapper.readTree(body).get("data");
    }

    private void expectConflict(String backtestId, long user, String code) throws Exception {
        for (String path : List.of("/timeseries", "/trades", "/weights", "/exceptions")) {
            mockMvc.perform(
                            get("/backtests/" + backtestId + path)
                                    .header(CurrentRequester.DEV_HEADER, user))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error.code").value(code));
        }
    }

    private static LocalDate lastPointDate(JsonNode series) {
        JsonNode points = series.get("points");
        return LocalDate.parse(points.get(points.size() - 1).get("date").asText());
    }

    // ---------------------------------------------------------------- scenarios

    @Test
    @DisplayName(
            "ⓐ·ⓑ 합류 요청 A 가 먼저 취소: 실행 중엔 하위 409 RESULT_NOT_READY, B 완료 뒤 A 는 스냅샷까지만·요약 없음, B 는 전체")
    void joinedCancelIsCutAtItsSnapshotAfterTheRunCompletes() throws Exception {
        long userA = newUser();
        long userB = newUser();
        BacktestRun run = runningWithProgress();
        String a = requestFor(run, userA, ResultSource.NEW);
        String b = requestFor(run, userB, ResultSource.JOINED);

        JsonNode accepted = cancel(a, userA);
        assertThat(accepted.get("status").asText()).isEqualTo("CANCELLED");
        assertThat(accepted.get("cancelRequested").asBoolean()).isFalse();
        verify(controlPublisher, never()).cancel(anyString());

        // ⓐ 실행은 B 를 위해 계속 돈다. A 의 상세는 200 CANCELLED, 하위는 결과 저장 전이라 409
        JsonNode aDetail = detail(a, userA);
        assertThat(aDetail.get("status").asText()).isEqualTo("CANCELLED");
        assertThat(aDetail.get("partial").asBoolean()).isTrue();
        assertThat(aDetail.get("progress").get("lastTradingDate").asText())
                .isEqualTo(PROGRESS_DATE.toString());
        assertThat(aDetail.get("summaryUnavailable").asText())
                .isEqualTo("CANCELLED_WHILE_RUN_CONTINUED");
        expectConflict(a, userA, "RESULT_NOT_READY");
        assertThat(detail(b, userB).get("status").asText()).isEqualTo("RUNNING");

        // ⓑ B 가 끝까지 돌아 완료됐다
        finished(run, MessageFixtures.FINISHED_COMPLETED);
        aDetail = detail(a, userA);
        assertThat(aDetail.get("status").asText()).isEqualTo("CANCELLED");
        assertThat(aDetail.get("progress").get("lastTradingDate").asText())
                .isEqualTo(PROGRESS_DATE.toString());
        assertThat(aDetail.get("summary").isNull()).isTrue();
        JsonNode aSeries = timeseries(a, userA);
        assertThat(aSeries.get("partial").asBoolean()).isTrue();
        assertThat(lastPointDate(aSeries)).isEqualTo(PROGRESS_DATE);
        assertThat(aSeries.get("points").size()).isLessThan(129);

        JsonNode bDetail = detail(b, userB);
        assertThat(bDetail.get("status").asText()).isEqualTo("COMPLETED");
        assertThat(bDetail.get("partial").asBoolean()).isFalse();
        assertThat(bDetail.get("summary").get("netTotalReturn").asText()).isEqualTo("0.166652");
        JsonNode bSeries = timeseries(b, userB);
        assertThat(bSeries.get("partial").asBoolean()).isFalse();
        assertThat(bSeries.get("points").size()).isEqualTo(129);
    }

    @Test
    @DisplayName("두 요청이 동시에 취소해도 마지막 판정은 직렬화된다 — CANCEL 은 정확히 한 번, stoppedRun 도 하나만")
    void concurrentCancelsStillStopTheRunExactlyOnce() throws Exception {
        long userA = newUser();
        long userB = newUser();
        BacktestRun run = runningWithProgress();
        String a = requestFor(run, userA, ResultSource.NEW);
        String b = requestFor(run, userB, ResultSource.JOINED);

        java.util.concurrent.CountDownLatch go = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.ExecutorService pool =
                java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            java.util.concurrent.Future<?> first =
                    pool.submit(
                            () -> {
                                go.await();
                                return cancelService.cancel(a, userA);
                            });
            java.util.concurrent.Future<?> second =
                    pool.submit(
                            () -> {
                                go.await();
                                return cancelService.cancel(b, userB);
                            });
            go.countDown();
            first.get(30, java.util.concurrent.TimeUnit.SECONDS);
            second.get(30, java.util.concurrent.TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        verify(controlPublisher, org.mockito.Mockito.times(1)).cancel(run.getBacktestId().value());
        List<BacktestRequest> requests = requestRepository.findByRunId(run.getId());
        assertThat(requests).allMatch(BacktestRequest::isCancelled);
        assertThat(requests.stream().filter(BacktestRequest::isStoppedRun).count()).isEqualTo(1);
        assertThat(
                        runRepository
                                .findById(run.getId())
                                .orElseThrow()
                                .getLifecycle()
                                .cancelRequestedAt())
                .isNotNull();
    }

    @Test
    @DisplayName("같은 요청을 동시에 두 번 취소해도 하나만 반영된다 — 남은 요청이 있는데 실행이 멈추면 안 된다")
    void concurrentCancelsOfTheSameRequestOnlyApplyOnce() throws Exception {
        long userA = newUser();
        long userB = newUser();
        BacktestRun run = runningWithProgress();
        String a = requestFor(run, userA, ResultSource.NEW);
        requestFor(run, userB, ResultSource.JOINED);

        java.util.concurrent.CyclicBarrier barrier = new java.util.concurrent.CyclicBarrier(2);
        java.util.concurrent.ExecutorService pool =
                java.util.concurrent.Executors.newFixedThreadPool(2);
        int failures = 0;
        try {
            List<java.util.concurrent.Future<Object>> futures =
                    List.of(
                            pool.submit(
                                    () -> {
                                        barrier.await();
                                        return cancelService.cancel(a, userA);
                                    }),
                            pool.submit(
                                    () -> {
                                        barrier.await();
                                        return cancelService.cancel(a, userA);
                                    }));
            for (java.util.concurrent.Future<Object> future : futures) {
                try {
                    future.get(30, java.util.concurrent.TimeUnit.SECONDS);
                } catch (java.util.concurrent.ExecutionException exc) {
                    failures++;
                    assertThat(exc.getCause()).isInstanceOf(BusinessException.class);
                    assertThat(((BusinessException) exc.getCause()).getErrorCode())
                            .isEqualTo(ErrorCode.NOT_CANCELLABLE);
                }
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(failures).isEqualTo(1);
        // B 가 아직 활성이므로 실행은 멈추지 않는다
        List<BacktestRequest> requests = requestRepository.findByRunId(run.getId());
        assertThat(requests.stream().filter(BacktestRequest::isCancelled).count()).isEqualTo(1);
        assertThat(requests).noneMatch(BacktestRequest::isStoppedRun);
        assertThat(
                        runRepository
                                .findById(run.getId())
                                .orElseThrow()
                                .getLifecycle()
                                .cancelRequestedAt())
                .isNull();
        verify(controlPublisher, never()).cancel(anyString());
    }

    @Test
    @DisplayName("동일 버전 조회로 이미 읽은 실행이라도 잠금 재조회는 그 사이 커밋된 취소 의도를 본다 — 취소 대기 실행에 합류하지 않는다")
    void aJoinArrivingDuringACancelIsRejected() {
        long owner = newUser();
        long latecomer = newUser();
        String accepted = backtestService.create(intake(owner)).backtestId();
        Long runId = requestRepository.findByBacktestId(accepted).orElseThrow().getRunId();

        TransactionTemplate cancelTx = new TransactionTemplate(transactionManager);
        cancelTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        // 접수가 실행을 읽은 뒤, 잠그기 전에 취소가 커밋되는 순서를 그대로 재현한다. 스레드 타이밍에
        // 기대지 않고 같은 트랜잭션 안에서 재현하므로 결과가 흔들리지 않는다.
        Throwable raised =
                new TransactionTemplate(transactionManager)
                        .execute(
                                status -> {
                                    // ① 접수가 동일 버전 조회로 실행을 읽는다(영속성 컨텍스트에 올라온다)
                                    assertThat(
                                                    runRepository
                                                            .findActiveByDedupKey(dedupKeyOf(runId))
                                                            .orElseThrow()
                                                            .getLifecycle()
                                                            .cancelRequestedAt())
                                            .isNull();
                                    // ② 그 사이 마지막 활성 요청이 취소해 의도가 커밋된다
                                    cancelTx.executeWithoutResult(
                                            inner ->
                                                    runRepository.recordCancelRequested(
                                                            runId, Instant.now()));
                                    // ③ 접수가 잠그고 다시 본다 — 여기서 최신 값을 보아야 한다
                                    try {
                                        backtestService.create(intake(latecomer));
                                        return null;
                                    } catch (RuntimeException exc) {
                                        status.setRollbackOnly();
                                        return (Throwable) exc;
                                    }
                                });

        assertThat(raised)
                .isInstanceOf(BusinessException.class)
                .extracting(exc -> ((BusinessException) exc).getErrorCode())
                .isEqualTo(ErrorCode.CANCEL_PENDING);
        assertThat(requestRepository.findByRunId(runId)).hasSize(1);
    }

    @Test
    @DisplayName("STARTED 가 실행을 읽은 뒤 취소가 커밋되면 CANCELLED 는 RUNNING 으로 되돌아가지 않는다")
    void aStartedArrivingDuringACancelDoesNotReviveTheRun() {
        long user = newUser();
        BacktestRun run = newRun();
        String id = requestFor(run, user, ResultSource.NEW);

        TransactionTemplate cancelTx = new TransactionTemplate(transactionManager);
        cancelTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        // 이벤트 처리가 실행을 읽은 뒤, 갱신하기 전에 취소가 커밋되는 순서를 그대로 재현한다.
        // 합류 쪽(aJoinArrivingDuringACancelIsRejected)과 같은 이유로 스레드 타이밍에 기대지 않는다.
        new TransactionTemplate(transactionManager)
                .executeWithoutResult(
                        status -> {
                            // ① STARTED 처리가 실행을 읽는다(영속성 컨텍스트에 QUEUED 로 올라온다)
                            assertThat(
                                            runRepository
                                                    .findByBacktestId(run.getBacktestId().value())
                                                    .orElseThrow()
                                                    .getStatus())
                                    .isEqualTo(RunStatus.QUEUED);
                            // ② 그 사이 대기 중 취소가 커밋된다
                            cancelTx.executeWithoutResult(inner -> cancelService.cancel(id, user));
                            // ③ STARTED 가 상태를 옮긴다 — 잠금 재조회로 최신 상태를 보아야 한다
                            started(run);
                        });

        assertThat(runRepository.findById(run.getId()).orElseThrow().getStatus())
                .isEqualTo(RunStatus.CANCELLED);
        verify(controlPublisher).cancel(run.getBacktestId().value());
    }

    @Test
    @DisplayName("ⓒ A 취소 뒤 실행이 FAILED 로 끝나면 A 는 CANCELLED 를 유지하고 하위는 409 BACKTEST_FAILED")
    void joinedCancelThenRunFails() throws Exception {
        long userA = newUser();
        long userB = newUser();
        BacktestRun run = runningWithProgress();
        String a = requestFor(run, userA, ResultSource.NEW);
        requestFor(run, userB, ResultSource.JOINED);

        cancel(a, userA);
        finished(run, MessageFixtures.FINISHED_FAILED);

        JsonNode aDetail = detail(a, userA);
        assertThat(aDetail.get("status").asText()).isEqualTo("CANCELLED");
        assertThat(aDetail.get("failure").get("code").asText()).isEqualTo("DATA_INSUFFICIENT");
        expectConflict(a, userA, "BACKTEST_FAILED");
    }

    @Test
    @DisplayName("ⓓ A 취소(d1) 뒤 B 가 마지막으로 취소: CANCEL 이 나가고 워커가 d2 에서 멈추면 A 는 d1·요약 없음, B 는 d2·부분 요약")
    void lastRequestCancelStopsTheRun() throws Exception {
        long userA = newUser();
        long userB = newUser();
        BacktestRun run = runningWithProgress();
        String a = requestFor(run, userA, ResultSource.NEW);
        String b = requestFor(run, userB, ResultSource.JOINED);

        cancel(a, userA);
        JsonNode bAccepted = cancel(b, userB);
        assertThat(bAccepted.get("cancelRequested").asBoolean()).isTrue();
        verify(controlPublisher).cancel(run.getBacktestId().value());
        assertThat(detail(b, userB).get("cancelRequested").asBoolean()).isTrue();
        expectConflict(b, userB, "RESULT_NOT_READY");

        // 워커가 d2 에서 멈추고 부분 결과를 보냈다
        finished(run, MessageFixtures.FINISHED_CANCELLED);

        JsonNode aDetail = detail(a, userA);
        assertThat(aDetail.get("progress").get("lastTradingDate").asText())
                .isEqualTo(PROGRESS_DATE.toString());
        assertThat(aDetail.get("summary").isNull()).isTrue();
        assertThat(lastPointDate(timeseries(a, userA))).isEqualTo(PROGRESS_DATE);

        JsonNode bDetail = detail(b, userB);
        assertThat(bDetail.get("status").asText()).isEqualTo("CANCELLED");
        assertThat(bDetail.get("partial").asBoolean()).isTrue();
        assertThat(bDetail.get("cancelRequested").asBoolean()).isFalse();
        assertThat(bDetail.get("progress").get("lastTradingDate").asText())
                .isEqualTo(STOPPED_DATE.toString());
        assertThat(bDetail.get("summary").isNull()).isFalse();
        assertThat(bDetail.get("summaryUnavailable").isNull()).isTrue();
        JsonNode bSeries = timeseries(b, userB);
        assertThat(bSeries.get("partial").asBoolean()).isTrue();
        assertThat(lastPointDate(bSeries)).isEqualTo(STOPPED_DATE);
        assertThat(runRepository.findById(run.getId()).orElseThrow().getStatus())
                .isEqualTo(RunStatus.CANCELLED);
    }

    @Test
    @DisplayName("취소를 보낸 실행이 재전달돼 다시 STARTED 를 보내면 CANCEL 을 다시 보낸다 — 워커의 취소 표시는 이미 지워졌다")
    void aRedeliveredCancelPendingRunGetsCancelAgain() throws Exception {
        long user = newUser();
        BacktestRun run = runningWithProgress();
        String id = requestFor(run, user, ResultSource.NEW);

        cancel(id, user);
        verify(controlPublisher, org.mockito.Mockito.times(1)).cancel(run.getBacktestId().value());

        // 워커가 죽어 브로커가 작업을 재전달했다(R-06). 새 실행은 취소 표시 없이 시작한다.
        started(run);

        verify(controlPublisher, org.mockito.Mockito.times(2)).cancel(run.getBacktestId().value());
        assertThat(runRepository.findById(run.getId()).orElseThrow().getStatus())
                .isEqualTo(RunStatus.RUNNING);
    }

    @Test
    @DisplayName(
            "ⓔ 첫 PROGRESS 전에 합류 요청을 취소하면 스냅샷 NULL — B 가 완료해도 A 는 완료 거래일 없음·409 NO_PARTIAL_RESULT")
    void cancelBeforeAnyProgressHasNoPartialResult() throws Exception {
        long userA = newUser();
        long userB = newUser();
        BacktestRun run = newRun();
        started(run);
        String a = requestFor(run, userA, ResultSource.NEW);
        requestFor(run, userB, ResultSource.JOINED);

        cancel(a, userA);
        finished(run, MessageFixtures.FINISHED_COMPLETED);

        JsonNode aDetail = detail(a, userA);
        assertThat(aDetail.get("status").asText()).isEqualTo("CANCELLED");
        assertThat(aDetail.get("progress").get("lastTradingDate").isNull()).isTrue();
        assertThat(aDetail.get("summary").isNull()).isTrue();
        expectConflict(a, userA, "NO_PARTIAL_RESULT");
    }

    @Test
    @DisplayName(
            "대기 중 취소: 실행이 바로 CANCELLED, 상세는 CANCELLED_BEFORE_START, 하위 409 NO_PARTIAL_RESULT, 늦은 STARTED 에 CANCEL 재발행")
    void queuedCancelFinishesTheRunWithoutAWorker() throws Exception {
        long user = newUser();
        BacktestRun run = newRun();
        String id = requestFor(run, user, ResultSource.NEW);

        JsonNode accepted = cancel(id, user);
        assertThat(accepted.get("cancelRequested").asBoolean()).isFalse();
        verify(controlPublisher, never()).cancel(anyString());
        assertThat(runRepository.findById(run.getId()).orElseThrow().getStatus())
                .isEqualTo(RunStatus.CANCELLED);

        JsonNode found = detail(id, user);
        assertThat(found.get("status").asText()).isEqualTo("CANCELLED");
        assertThat(found.get("summaryUnavailable").asText()).isEqualTo("CANCELLED_BEFORE_START");
        assertThat(found.get("summary").isNull()).isTrue();
        expectConflict(id, user, "NO_PARTIAL_RESULT");

        // 큐에 남아 있던 작업을 워커가 집어 STARTED 를 보냈다 — 백엔드가 CANCEL 을 다시 보낸다
        started(run);
        verify(controlPublisher).cancel(run.getBacktestId().value());
        assertThat(runRepository.findById(run.getId()).orElseThrow().getStatus())
                .isEqualTo(RunStatus.CANCELLED);

        // 두 번 취소는 409
        mockMvc.perform(
                        post("/backtests/{id}/cancel", id)
                                .header(CurrentRequester.DEV_HEADER, user))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("NOT_CANCELLABLE"));
    }
}
