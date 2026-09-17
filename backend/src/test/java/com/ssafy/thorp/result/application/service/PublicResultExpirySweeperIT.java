package com.ssafy.thorp.result.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.ssafy.thorp.backtest.application.command.SaveBacktestResultCommand;
import com.ssafy.thorp.backtest.application.port.BacktestJobPublisher;
import com.ssafy.thorp.backtest.application.repository.BacktestRequestRepository;
import com.ssafy.thorp.backtest.application.repository.BacktestResultRepository;
import com.ssafy.thorp.backtest.application.repository.BacktestReuseEventRepository;
import com.ssafy.thorp.backtest.application.repository.BacktestRunRepository;
import com.ssafy.thorp.backtest.domain.model.BacktestRequest;
import com.ssafy.thorp.backtest.domain.model.BacktestReuseEvent;
import com.ssafy.thorp.backtest.domain.model.BacktestRun;
import com.ssafy.thorp.backtest.domain.model.ExecutionType;
import com.ssafy.thorp.backtest.domain.model.PositionSizingMode;
import com.ssafy.thorp.backtest.domain.model.PublicResultToken;
import com.ssafy.thorp.backtest.domain.model.ResultSource;
import com.ssafy.thorp.backtest.domain.vo.BacktestPeriod;
import com.ssafy.thorp.backtest.domain.vo.InstrumentId;
import com.ssafy.thorp.backtest.infrastructure.messaging.MessageFixtures;
import com.ssafy.thorp.result.application.repository.ExpiredPublicTokenRepository;
import com.ssafy.thorp.support.PostgresTestSupport;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 만료된 비로그인 요청 정리를 실제 PostgreSQL 외래 키로 본다(spec 005 T026·T087·T088, FR-021).
 *
 * <p>목으로는 확인할 수 없는 것이 핵심이다 — 재사용 기록·재시도 요청이 요청 행을 외래 키로 가리키므로 지우는 순서가 틀리면 DB가 거절한다. 그리고 <b>같은 실행을
 * 가리키는 로그인 요청과 실행·결과는 남아야 한다</b>(004 FR-003). 지운 토큰은 해시 묘비로 남아야 한다(T087).
 *
 * <p>스케줄러는 끄고 시각을 고정한 인스턴스를 직접 만든다. 만료 시각을 2000년대 초로 두어 같은 컨테이너를 쓰는 다른 IT가 남긴 만료 행보다 앞에 오게 한다 — 정리는
 * 만료가 이른 순서로 배치 크기만큼 지운다.
 */
@SpringBootTest(
        properties = {
            "spring.rabbitmq.listener.simple.auto-startup=false",
            "thorp.backtest.republish.enabled=false",
            "thorp.result.expiry-sweep.enabled=false",
            "spring.mail.username=test@example.com",
            "spring.mail.password=test"
        })
class PublicResultExpirySweeperIT extends PostgresTestSupport {

    private static final Duration RETENTION = Duration.ofDays(30);

    @Autowired private BacktestRunRepository runRepository;
    @Autowired private BacktestRequestRepository requestRepository;
    @Autowired private BacktestReuseEventRepository reuseEventRepository;
    @Autowired private BacktestResultRepository resultRepository;
    @Autowired private ExpiredPublicTokenRepository expiredTokens;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private TransactionTemplate transactionTemplate;
    @Autowired private JdbcTemplate jdbcTemplate;

    /** 브로커는 띄우지 않는다. 이 IT는 접수를 부르지 않지만 발행기 빈이 브로커에 붙지 않게 목으로 둔다. */
    @MockitoBean private BacktestJobPublisher publisher;

    private PublicResultExpirySweeper sweeperAt(Instant now, int batchSize) {
        return new PublicResultExpirySweeper(
                requestRepository,
                reuseEventRepository,
                expiredTokens,
                transactionManager,
                Clock.fixed(now, ZoneOffset.UTC),
                batchSize,
                RETENTION);
    }

    private BacktestRun newRun() {
        return transactionTemplate.execute(
                status ->
                        runRepository.save(
                                BacktestRun.create(
                                        "EQUAL_WEIGHT",
                                        "1.0.0",
                                        Map.of("rebalancePeriod", "MONTHLY"),
                                        List.of(new InstrumentId("005930.XKRX")),
                                        new BacktestPeriod(
                                                LocalDate.of(2025, 1, 1),
                                                LocalDate.of(2025, 6, 30)),
                                        new BigDecimal("10000000"),
                                        PositionSizingMode.STRATEGY_DEFINED,
                                        new BigDecimal("0.50"),
                                        new InstrumentId("069500.XKRX"),
                                        42)));
    }

    /** 결과 행이 붙은 완료 실행. 결과 저장은 한 트랜잭션 안에서 해야 한다({@code BacktestResultApiIT}와 같다). */
    private BacktestRun completedRun() {
        return transactionTemplate.execute(
                status -> {
                    BacktestRun run = newRun();
                    var event = MessageFixtures.finishedCompleted();
                    resultRepository.save(
                            new SaveBacktestResultCommand(
                                    run.getId(),
                                    event.executionId(),
                                    event.eventId(),
                                    event.payload().engineOutput(),
                                    null));
                    return runRepository.update(run.markCompleted());
                });
    }

    /** 결과 행이 없는 종결 실행. 정리 작업은 실행이 끝난 요청만 지운다(T093). */
    private BacktestRun finishedRun() {
        BacktestRun run = newRun();
        return transactionTemplate.execute(
                status -> runRepository.update(run.markFailed("DATA_INSUFFICIENT", "no data")));
    }

    private long newUser() {
        String suffix = Long.toString(System.nanoTime() % 1_000_000_000L);
        String email = "sweeper-" + suffix + "@example.com";
        jdbcTemplate.update(
                "INSERT INTO users (email, password_hash, nickname, phone_number, status,"
                        + " paper_ranking_enabled, live_ranking_enabled, ranking_display_nickname,"
                        + " created_at, updated_at)"
                        + " VALUES (?, 'x', ?, ?, 'ACTIVE', false, false, false, NOW(), NOW())",
                email,
                "s" + suffix,
                suffix);
        return jdbcTemplate.queryForObject(
                "SELECT id FROM users WHERE email = ?", Long.class, email);
    }

    private BacktestRequest request(
            BacktestRun run, Long requester, Long retryOf, ResultSource source, Instant expiresAt) {
        return transactionTemplate.execute(
                status ->
                        requestRepository.save(
                                BacktestRequest.create(
                                        run.getId(),
                                        requester,
                                        ExecutionType.SINGLE,
                                        source,
                                        retryOf,
                                        PublicResultToken.generate(),
                                        expiresAt)));
    }

    private boolean exists(BacktestRequest request) {
        return count("SELECT count(*) FROM backtest_request WHERE id = ?", request.getId()) > 0;
    }

    private long count(String sql, Object... args) {
        return jdbcTemplate.queryForObject(sql, Long.class, args);
    }

    /** 해시를 DB가 따로 계산해 찾는다. 코드의 해시 함수를 그대로 쓰면 두 쪽이 같이 틀려도 통과한다. null = 묘비 없음. */
    private Instant tombstoneExpiry(BacktestRequest request) {
        List<Timestamp> found =
                jdbcTemplate.queryForList(
                        "SELECT expired_at FROM expired_public_token"
                                + " WHERE token_hash = encode(sha256(convert_to(?, 'UTF8')), 'hex')",
                        Timestamp.class,
                        request.getPublicToken().value());
        return found.isEmpty() ? null : found.get(0).toInstant();
    }

    @Test
    @DisplayName(
            "만료된 비로그인 요청만 지우고 같은 실행의 로그인 요청·실행·결과는 남긴다. 재사용 기록은 지우고 재시도의 참조는 끊고, 토큰은 해시 묘비로 남긴다")
    void deletesExpiredAnonymousRequestOnly() {
        Instant now = Instant.parse("2001-06-01T00:00:00Z");
        Instant expiredAt = Instant.parse("2001-05-20T00:00:00Z");
        BacktestRun run = completedRun();
        long user = transactionTemplate.execute(status -> newUser());

        BacktestRequest loggedIn = request(run, user, null, ResultSource.NEW, null);
        BacktestRequest expired = request(run, null, null, ResultSource.REUSED, expiredAt);
        transactionTemplate.executeWithoutResult(
                status ->
                        reuseEventRepository.save(
                                new BacktestReuseEvent(
                                        null,
                                        run.getId(),
                                        expired.getId(),
                                        ResultSource.REUSED,
                                        ExecutionType.SINGLE,
                                        expiredAt.minus(Duration.ofDays(7)))));
        BacktestRequest retryChild =
                request(
                        run,
                        null,
                        expired.getId(),
                        ResultSource.NEW,
                        Instant.parse("2099-01-01T00:00:00Z"));
        BacktestRequest notYetExpired =
                request(run, null, null, ResultSource.NEW, Instant.parse("2099-01-01T00:00:00Z"));

        sweeperAt(now, 1000).sweep();

        assertThat(exists(expired)).isFalse();
        assertThat(exists(loggedIn)).isTrue();
        assertThat(exists(retryChild)).isTrue();
        assertThat(exists(notYetExpired)).isTrue();
        assertThat(
                        count(
                                "SELECT count(*) FROM backtest_reuse_event WHERE request_id = ?",
                                expired.getId()))
                .isZero();
        assertThat(
                        jdbcTemplate.queryForObject(
                                "SELECT retry_of_request_id FROM backtest_request WHERE id = ?",
                                Long.class,
                                retryChild.getId()))
                .as("재시도 요청은 남고 원본 참조만 끊긴다")
                .isNull();
        assertThat(count("SELECT count(*) FROM backtest_run WHERE id = ?", run.getId()))
                .as("실행은 다른 요청이 볼 수 있어 지우지 않는다")
                .isEqualTo(1);
        assertThat(count("SELECT count(*) FROM backtest_result WHERE run_id = ?", run.getId()))
                .as("결과도 지우지 않는다 (T088)")
                .isEqualTo(1);

        assertThat(tombstoneExpiry(expired)).as("지운 토큰은 묘비로 남는다 (T087)").isEqualTo(expiredAt);
        assertThat(tombstoneExpiry(notYetExpired)).as("지우지 않은 토큰은 묘비가 없다").isNull();
        assertThat(
                        count(
                                "SELECT count(*) FROM expired_public_token WHERE token_hash = ?",
                                expired.getPublicToken().value()))
                .as("토큰 원문은 저장하지 않는다")
                .isZero();
    }

    @Test
    @DisplayName("만료됐어도 가리키는 실행이 끝나지 않았으면 지우지 않고, 끝난 뒤 다음 주기에 지운다 — 비로그인 대기 몫에서 빠지지 않는다 (T093)")
    void keepsExpiredRequestWhileItsRunIsUnfinished() {
        Instant expiresAt = Instant.parse("2003-01-01T00:00:00Z");
        BacktestRun queued = newRun();
        BacktestRequest waiting = request(queued, null, null, ResultSource.NEW, expiresAt);

        sweeperAt(expiresAt.plusSeconds(60), 1000).sweep();
        assertThat(exists(waiting)).as("QUEUED 실행의 요청은 남는다").isTrue();
        assertThat(tombstoneExpiry(waiting)).as("지우지 않았으니 묘비도 없다").isNull();

        transactionTemplate.executeWithoutResult(
                status ->
                        runRepository.update(
                                runRepository
                                        .findById(queued.getId())
                                        .orElseThrow()
                                        .markFailed("DATA_INSUFFICIENT", "no data")));
        sweeperAt(expiresAt.plusSeconds(120), 1000).sweep();
        assertThat(exists(waiting)).isFalse();
        assertThat(tombstoneExpiry(waiting)).isEqualTo(expiresAt);
    }

    @Test
    @DisplayName("만료 시각과 같은 순간부터 지운다 — 조회의 410 경계와 같다")
    void expiryBoundaryIsInclusive() {
        Instant expiresAt = Instant.parse("2002-01-01T00:00:00Z");
        BacktestRequest atBoundary =
                request(finishedRun(), null, null, ResultSource.NEW, expiresAt);

        sweeperAt(expiresAt.minusMillis(1), 1000).sweep();
        assertThat(exists(atBoundary)).isTrue();

        sweeperAt(expiresAt, 1000).sweep();
        assertThat(exists(atBoundary)).isFalse();
    }

    @Test
    @DisplayName("한 주기에 배치 크기만큼, 만료가 이른 것부터 지운다")
    void deletesAtMostBatchSize() {
        BacktestRun run = finishedRun();
        BacktestRequest first =
                request(run, null, null, ResultSource.NEW, Instant.parse("2000-01-01T00:00:00Z"));
        BacktestRequest second =
                request(run, null, null, ResultSource.NEW, Instant.parse("2000-01-02T00:00:00Z"));
        BacktestRequest third =
                request(run, null, null, ResultSource.NEW, Instant.parse("2000-01-03T00:00:00Z"));

        sweeperAt(Instant.parse("2000-02-01T00:00:00Z"), 2).sweep();

        assertThat(exists(first)).isFalse();
        assertThat(exists(second)).isFalse();
        assertThat(exists(third)).isTrue();
    }

    @Test
    @DisplayName("보관 기한이 지난 묘비만 지우고, 같은 토큰을 두 번 기록해도 실패하지 않는다 (T087)")
    void prunesTombstonesPastRetention() {
        // 다른 시험이 남긴 묘비(2000년 이후)보다 앞선 시각이라 이 시험의 두 묘비만 판정 대상이다.
        String old = "a".repeat(63) + "1";
        String recent = "a".repeat(63) + "2";
        Instant now = Instant.parse("1999-06-01T00:00:00Z");
        Instant recentExpiry = now.minus(RETENTION).plusSeconds(1);
        transactionTemplate.executeWithoutResult(
                status -> {
                    expiredTokens.record(old, now.minus(RETENTION).minusSeconds(1), now);
                    expiredTokens.record(recent, recentExpiry, now);
                    // 두 인스턴스가 같은 요청을 동시에 정리한 경우다. 기존 묘비를 덮거나 예외를 내지 않는다.
                    expiredTokens.record(recent, now, now);
                });

        sweeperAt(now, 1000).sweep();

        assertThat(expiredTokens.exists(old)).isFalse();
        assertThat(expiredTokens.exists(recent)).isTrue();
        assertThat(
                        jdbcTemplate
                                .queryForObject(
                                        "SELECT expired_at FROM expired_public_token"
                                                + " WHERE token_hash = ?",
                                        Timestamp.class,
                                        recent)
                                .toInstant())
                .isEqualTo(recentExpiry);
    }
}
