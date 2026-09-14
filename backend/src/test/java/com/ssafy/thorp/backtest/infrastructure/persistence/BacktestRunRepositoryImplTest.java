package com.ssafy.thorp.backtest.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ssafy.thorp.backtest.domain.model.BacktestRun;
import com.ssafy.thorp.backtest.domain.model.PositionSizingMode;
import com.ssafy.thorp.backtest.domain.model.RunStatus;
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
@Import({JpaAuditingConfig.class, BacktestRunRepositoryImpl.class})
class BacktestRunRepositoryImplTest extends PostgresTestSupport {

    private static final LocalDate TODAY = LocalDate.of(2026, 9, 7);
    private static final BacktestPeriod PERIOD =
            new BacktestPeriod(LocalDate.of(2019, 1, 1), LocalDate.of(2023, 12, 31));
    private static final Map<String, Object> PARAMETERS =
            Map.of("shortWindow", 20, "threshold", 0.05, "mode", "cross");
    private static final List<InstrumentId> INSTRUMENTS =
            List.of(new InstrumentId("005930.XKRX"), new InstrumentId("000660.XKRX"));
    private static final BigDecimal CASH = new BigDecimal("10000000.00");

    private static BacktestRun newJob() {
        return BacktestRun.create(
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

    @Autowired private BacktestRunRepositoryImpl repository;
    @Autowired private TestEntityManager entityManager;

    @Test
    @DisplayName("save 후 findById로 JSON 컬럼까지 같은 값으로 읽어온다")
    void saveThenFindById() {
        BacktestRun saved = repository.save(newJob());
        entityManager.flush();
        entityManager.clear();

        assertThat(saved.getId()).isNotNull();
        Optional<BacktestRun> found = repository.findById(saved.getId());
        assertThat(found).isPresent();
        BacktestRun job = found.get();
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
        assertThat(job.getStatus()).isEqualTo(RunStatus.QUEUED);
    }

    @Test
    @DisplayName("save는 고정 Clock의 날짜와 PK로 backtestId를 부여하고 NOT NULL인 채로 INSERT된다")
    void saveAssignsBacktestIdBeforeInsert() {
        BacktestRun saved = repository.save(newJob());
        entityManager.flush();
        entityManager.clear();

        assertThat(saved.getBacktestId()).isEqualTo(BacktestId.of(TODAY, saved.getId()));
        assertThat(saved.getBacktestId().value()).startsWith("BT-20260907-");

        BacktestRunJpaEntity entity = entityManager.find(BacktestRunJpaEntity.class, saved.getId());
        assertThat(entity.getBacktestId()).isEqualTo(saved.getBacktestId().value());
    }

    @Test
    @DisplayName("findByBacktestId로 문자열 식별자 조회가 된다")
    void findByBacktestId() {
        BacktestRun saved = repository.save(newJob());
        entityManager.flush();
        entityManager.clear();

        Optional<BacktestRun> found = repository.findByBacktestId(saved.getBacktestId().value());
        assertThat(found).isPresent();
        assertThat(found.get().getId()).isEqualTo(saved.getId());
    }

    @Test
    @DisplayName("update는 상태 전이를 저장하고 나머지 컬럼은 그대로 둔다")
    void updatePersistsStatusTransition() {
        BacktestRun saved = repository.save(newJob());
        entityManager.flush();
        entityManager.clear();

        BacktestRun updated = repository.update(saved.markRunning("EXEC-000001"));
        entityManager.flush();
        entityManager.clear();

        assertThat(updated.getStatus()).isEqualTo(RunStatus.RUNNING);
        Optional<BacktestRun> found = repository.findById(saved.getId());
        assertThat(found).isPresent();
        BacktestRun job = found.get();
        assertThat(job.getStatus()).isEqualTo(RunStatus.RUNNING);
        assertThat(job.getBacktestId()).isEqualTo(saved.getBacktestId());
        assertThat(job.getParameters()).isEqualTo(PARAMETERS);
        assertThat(job.getInstrumentIds()).isEqualTo(INSTRUMENTS);
        assertThat(job.getInitialCash()).isEqualByComparingTo(CASH);

        BacktestRunJpaEntity entity = entityManager.find(BacktestRunJpaEntity.class, saved.getId());
        assertThat(entity.getCreatedAt()).as("createdAt은 갱신되지 않는다").isNotNull();
    }

    @Test
    @DisplayName("update는 저장된 Job 전용이라 id가 없거나 없는 id면 거부한다")
    void updateRejectsUnknownJob() {
        assertThatThrownBy(() -> repository.update(newJob()))
                .isInstanceOf(IllegalArgumentException.class);

        BacktestRun missing =
                BacktestRun.restore(
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
                        RunStatus.RUNNING);
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
        BacktestRun saved = repository.save(newJob());
        entityManager.flush();

        entityManager.persist(
                new BacktestRunJpaEntity(
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
                        RunStatus.QUEUED,
                        0,
                        null,
                        false,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null));

        assertThatThrownBy(() -> entityManager.flush()).isInstanceOf(Exception.class);
    }

    @Test
    @DisplayName("save는 신규 Job 전용이라 id가 있으면 거부한다")
    void saveRejectsExistingJob() {
        BacktestRun existing =
                BacktestRun.restore(
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
                        RunStatus.QUEUED);

        assertThatThrownBy(() -> repository.save(existing))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("이미 backtestId가 있는 엔티티에는 다시 부여할 수 없다")
    void assignBacktestIdOnlyOnce() {
        BacktestRunJpaEntity entity =
                new BacktestRunJpaEntity(
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
                        RunStatus.QUEUED,
                        0,
                        null,
                        false,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null);

        assertThatThrownBy(() -> entity.assignBacktestIdOn(TODAY))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("BaseEntity의 생성·수정 시각이 Auditing으로 채워진다")
    void auditingFieldsArePopulated() {
        BacktestRun saved = repository.save(newJob());
        entityManager.flush();

        BacktestRunJpaEntity entity = entityManager.find(BacktestRunJpaEntity.class, saved.getId());
        assertThat(entity.getCreatedAt()).isNotNull();
        assertThat(entity.getUpdatedAt()).isNotNull();
    }

    /**
     * 상태 저장과 발행 기록이 서로의 컬럼을 건드리지 않는지(spec 004 R-18).
     *
     * <p><b>이 테스트만으로는 모자란다.</b> 중간에 영속성 컨텍스트를 비우므로 저장 시점에 최신 엔티티를 다시 읽는다. 실제 사고는 이벤트 트랜잭션이 실행을
     * <b>읽어 둔 채로</b> 진행되는 동안 발행 경로가 끼어들 때 일어나고, 그때는 Hibernate 가 바꾸지 않은 컬럼까지 옛 값으로 함께 쓴다(그래서 엔티티에
     * {@code @DynamicUpdate} 를 걸었다). 그쪽은 {@code
     * BacktestPublishRecordIT.staleContextDoesNotResetPublishCount} 가 실제 커밋으로 확인한다. 여기서 보는 것은 "저장소
     * 메서드가 남의 컬럼을 인자로 받지 않는다"는 더 좁은 성질이다.
     */
    @Test
    @DisplayName("상태 저장이 발행 기록을 되돌리지 않는다 — 오래된 스냅샷으로 저장해도 시도 횟수가 남는다")
    void updateDoesNotOverwriteDeliveryColumns() {
        BacktestRun saved = repository.save(newJob());
        entityManager.flush();

        // 발행 경로가 먼저 시도 횟수와 확인 시각을 남긴다.
        repository.recordPublishAttempt(saved.getId());
        repository.recordPublished(saved.getId(), Instant.parse("2026-09-07T03:00:05Z"));
        entityManager.flush();
        entityManager.clear();

        // 이벤트 경로는 그 전에 읽어 둔 오래된 객체로 상태를 저장한다.
        repository.update(saved.markRunning("exec-1"));
        entityManager.flush();
        entityManager.clear();

        BacktestRunJpaEntity entity = entityManager.find(BacktestRunJpaEntity.class, saved.getId());
        assertThat(entity.getStatus()).isEqualTo(RunStatus.RUNNING);
        assertThat(entity.getPublishCount()).as("발행 시도 횟수가 되돌아가면 안 된다").isEqualTo(1);
        assertThat(entity.getPublishedAt()).as("발행 확인이 지워지면 재발행기가 다시 보낸다").isNotNull();
    }

    @Test
    @DisplayName("발행 기록이 실행 상태를 되돌리지 않는다")
    void deliveryUpdateDoesNotOverwriteState() {
        BacktestRun saved = repository.save(newJob());
        entityManager.flush();
        repository.update(saved.markRunning("exec-1"));
        entityManager.flush();
        entityManager.clear();

        repository.recordPublishAttempt(saved.getId());
        repository.recordPublished(saved.getId(), Instant.parse("2026-09-07T03:00:05Z"));
        entityManager.flush();
        entityManager.clear();

        BacktestRunJpaEntity entity = entityManager.find(BacktestRunJpaEntity.class, saved.getId());
        assertThat(entity.getStatus()).isEqualTo(RunStatus.RUNNING);
        assertThat(entity.getCurrentExecutionId()).isEqualTo("exec-1");
    }
}
