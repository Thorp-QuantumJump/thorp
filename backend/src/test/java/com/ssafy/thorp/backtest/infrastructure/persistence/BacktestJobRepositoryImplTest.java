package com.ssafy.thorp.backtest.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ssafy.thorp.backtest.domain.model.BacktestJob;
import com.ssafy.thorp.backtest.domain.model.BacktestStatus;
import com.ssafy.thorp.backtest.domain.model.PositionSizingMode;
import com.ssafy.thorp.backtest.domain.vo.BacktestId;
import com.ssafy.thorp.backtest.domain.vo.BacktestPeriod;
import com.ssafy.thorp.backtest.domain.vo.InstrumentId;
import com.ssafy.thorp.common.config.JpaAuditingConfig;
import com.ssafy.thorp.support.PostgresTestSupport;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

/**
 * PostgreSQL 라운드트립. {@code @DataJpaTest}는 {@code @Configuration}·{@code @Repository} 구현체를 스캔하지 않으므로
 * 둘을 명시 등록한다. Auditing이 없으면 {@code created_at NOT NULL} 제약에 걸려 저장이 실패한다.
 *
 * <p>Clock은 고정해 {@code backtestId}의 날짜 접두를 단언할 수 있게 한다. 순번은 시퀀스라 테스트 간에 이어지므로 값이 아니라 저장된 PK로 만든 값과
 * 같은지를 본다.
 */
@DataJpaTest
@Import({JpaAuditingConfig.class, BacktestJobRepositoryImpl.class})
class BacktestJobRepositoryImplTest extends PostgresTestSupport {

    private static final LocalDate TODAY = LocalDate.of(2026, 9, 7);
    private static final BacktestPeriod PERIOD =
            new BacktestPeriod(LocalDate.of(2019, 1, 1), LocalDate.of(2023, 12, 31));
    private static final Map<String, Object> PARAMETERS =
            Map.of("shortWindow", 20, "threshold", 0.05, "mode", "cross");
    private static final List<InstrumentId> INSTRUMENTS =
            List.of(new InstrumentId("005930.XKRX"), new InstrumentId("000660.XKRX"));
    private static final BigDecimal CASH = new BigDecimal("10000000.00");

    private static BacktestJob newJob() {
        return BacktestJob.create(
                "MA_CROSSOVER",
                "1.0.0",
                PARAMETERS,
                INSTRUMENTS,
                PERIOD,
                CASH,
                PositionSizingMode.STRATEGY_DEFINED,
                new BigDecimal("0.5000"),
                new InstrumentId("069500.XKRX"),
                42);
    }

    @TestConfiguration
    static class FixedClockConfig {
        @Bean
        Clock clock() {
            return Clock.fixed(Instant.parse("2026-09-07T03:00:00Z"), ZoneId.of("Asia/Seoul"));
        }
    }

    @Autowired private BacktestJobRepositoryImpl repository;
    @Autowired private TestEntityManager entityManager;

    @Test
    @DisplayName("save 후 findById로 JSON 컬럼까지 같은 값으로 읽어온다")
    void saveThenFindById() {
        BacktestJob saved = repository.save(newJob());
        entityManager.flush();
        entityManager.clear();

        assertThat(saved.getId()).isNotNull();
        Optional<BacktestJob> found = repository.findById(saved.getId());
        assertThat(found).isPresent();
        BacktestJob job = found.get();
        assertThat(job.getBacktestId()).isEqualTo(saved.getBacktestId());
        assertThat(job.getStrategyId()).isEqualTo("MA_CROSSOVER");
        assertThat(job.getStrategyVersion()).isEqualTo("1.0.0");
        assertThat(job.getParameters()).isEqualTo(PARAMETERS);
        assertThat(job.getInstrumentIds()).isEqualTo(INSTRUMENTS);
        assertThat(job.getPeriod()).isEqualTo(PERIOD);
        assertThat(job.getInitialCash()).isEqualByComparingTo(CASH);
        assertThat(job.getPositionSizingType()).isEqualTo(PositionSizingMode.STRATEGY_DEFINED);
        assertThat(job.getMaxPositionPct()).isEqualByComparingTo("0.5000");
        assertThat(job.getBenchmarkInstrumentId()).isEqualTo(new InstrumentId("069500.XKRX"));
        assertThat(job.getRandomSeed()).isEqualTo(42);
        assertThat(job.getStatus()).isEqualTo(BacktestStatus.QUEUED);
    }

    @Test
    @DisplayName("save는 고정 Clock의 날짜와 PK로 backtestId를 부여하고 NOT NULL인 채로 INSERT된다")
    void saveAssignsBacktestIdBeforeInsert() {
        BacktestJob saved = repository.save(newJob());
        entityManager.flush();
        entityManager.clear();

        assertThat(saved.getBacktestId()).isEqualTo(BacktestId.of(TODAY, saved.getId()));
        assertThat(saved.getBacktestId().value()).startsWith("BT-20260907-");

        BacktestJobJpaEntity entity = entityManager.find(BacktestJobJpaEntity.class, saved.getId());
        assertThat(entity.getBacktestId()).isEqualTo(saved.getBacktestId().value());
    }

    @Test
    @DisplayName("findByBacktestId로 문자열 식별자 조회가 된다")
    void findByBacktestId() {
        BacktestJob saved = repository.save(newJob());
        entityManager.flush();
        entityManager.clear();

        Optional<BacktestJob> found = repository.findByBacktestId(saved.getBacktestId().value());
        assertThat(found).isPresent();
        assertThat(found.get().getId()).isEqualTo(saved.getId());
    }

    @Test
    @DisplayName("update는 상태 전이를 저장하고 나머지 컬럼은 그대로 둔다")
    void updatePersistsStatusTransition() {
        BacktestJob saved = repository.save(newJob());
        entityManager.flush();
        entityManager.clear();

        BacktestJob updated = repository.update(saved.markRunning());
        entityManager.flush();
        entityManager.clear();

        assertThat(updated.getStatus()).isEqualTo(BacktestStatus.RUNNING);
        Optional<BacktestJob> found = repository.findById(saved.getId());
        assertThat(found).isPresent();
        BacktestJob job = found.get();
        assertThat(job.getStatus()).isEqualTo(BacktestStatus.RUNNING);
        assertThat(job.getBacktestId()).isEqualTo(saved.getBacktestId());
        assertThat(job.getParameters()).isEqualTo(PARAMETERS);
        assertThat(job.getInstrumentIds()).isEqualTo(INSTRUMENTS);
        assertThat(job.getInitialCash()).isEqualByComparingTo(CASH);

        BacktestJobJpaEntity entity = entityManager.find(BacktestJobJpaEntity.class, saved.getId());
        assertThat(entity.getCreatedAt()).as("createdAt은 갱신되지 않는다").isNotNull();
    }

    @Test
    @DisplayName("update는 저장된 Job 전용이라 id가 없거나 없는 id면 거부한다")
    void updateRejectsUnknownJob() {
        assertThatThrownBy(() -> repository.update(newJob()))
                .isInstanceOf(IllegalArgumentException.class);

        BacktestJob missing =
                BacktestJob.restore(
                        999_999L,
                        BacktestId.from("BT-20260907-999999"),
                        "MA_CROSSOVER",
                        "1.0.0",
                        PARAMETERS,
                        INSTRUMENTS,
                        PERIOD,
                        CASH,
                        PositionSizingMode.STRATEGY_DEFINED,
                        null,
                        null,
                        42,
                        BacktestStatus.RUNNING);
        assertThatThrownBy(() -> repository.update(missing))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("없는 식별자는 빈 Optional을 돌려준다")
    void findReturnsEmptyWhenMissing() {
        assertThat(repository.findById(999L)).isEmpty();
        assertThat(repository.findByBacktestId("BT-20260907-999999")).isEmpty();
    }

    @Test
    @DisplayName("backtest_id가 겹치면 UNIQUE 제약에 걸린다")
    void rejectsDuplicateBacktestId() {
        BacktestJob saved = repository.save(newJob());
        entityManager.flush();

        entityManager.persist(
                new BacktestJobJpaEntity(
                        null,
                        saved.getBacktestId().value(),
                        "MA_CROSSOVER",
                        "1.0.0",
                        PARAMETERS,
                        List.of("005930.XKRX"),
                        PERIOD.startDate(),
                        PERIOD.endDate(),
                        CASH,
                        PositionSizingMode.STRATEGY_DEFINED,
                        null,
                        null,
                        42,
                        BacktestStatus.QUEUED));

        assertThatThrownBy(() -> entityManager.flush()).isInstanceOf(Exception.class);
    }

    @Test
    @DisplayName("save는 신규 Job 전용이라 id가 있으면 거부한다")
    void saveRejectsExistingJob() {
        BacktestJob existing =
                BacktestJob.restore(
                        1L,
                        BacktestId.from("BT-20260907-000001"),
                        "MA_CROSSOVER",
                        "1.0.0",
                        PARAMETERS,
                        INSTRUMENTS,
                        PERIOD,
                        CASH,
                        PositionSizingMode.STRATEGY_DEFINED,
                        null,
                        null,
                        42,
                        BacktestStatus.QUEUED);

        assertThatThrownBy(() -> repository.save(existing))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("이미 backtestId가 있는 엔티티에는 다시 부여할 수 없다")
    void assignBacktestIdOnlyOnce() {
        BacktestJobJpaEntity entity =
                new BacktestJobJpaEntity(
                        null,
                        "BT-20260907-000001",
                        "MA_CROSSOVER",
                        "1.0.0",
                        PARAMETERS,
                        List.of("005930.XKRX"),
                        PERIOD.startDate(),
                        PERIOD.endDate(),
                        CASH,
                        PositionSizingMode.STRATEGY_DEFINED,
                        null,
                        null,
                        42,
                        BacktestStatus.QUEUED);

        assertThatThrownBy(() -> entity.assignBacktestIdOn(TODAY))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("BaseEntity의 생성·수정 시각이 Auditing으로 채워진다")
    void auditingFieldsArePopulated() {
        BacktestJob saved = repository.save(newJob());
        entityManager.flush();

        BacktestJobJpaEntity entity = entityManager.find(BacktestJobJpaEntity.class, saved.getId());
        assertThat(entity.getCreatedAt()).isNotNull();
        assertThat(entity.getUpdatedAt()).isNotNull();
    }
}
