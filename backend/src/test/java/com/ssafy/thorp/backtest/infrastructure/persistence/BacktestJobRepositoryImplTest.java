package com.ssafy.thorp.backtest.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ssafy.thorp.backtest.domain.model.BacktestJob;
import com.ssafy.thorp.backtest.domain.model.BacktestStatus;
import com.ssafy.thorp.backtest.domain.vo.BacktestId;
import com.ssafy.thorp.backtest.domain.vo.BacktestPeriod;
import com.ssafy.thorp.common.config.JpaAuditingConfig;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
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
 * H2 라운드트립. {@code @DataJpaTest}는 {@code @Configuration}·{@code @Repository} 구현체를 스캔하지 않으므로 둘을 명시
 * 등록한다. Auditing이 없으면 {@code created_at NOT NULL} 제약에 걸려 저장이 실패한다.
 *
 * <p>Clock은 고정해 {@code backtestId}의 날짜 접두를 단언할 수 있게 한다. 순번은 시퀀스라 테스트 간에 이어지므로 값이 아니라 저장된 PK로 만든 값과
 * 같은지를 본다.
 */
@DataJpaTest
@Import({JpaAuditingConfig.class, BacktestJobRepositoryImpl.class})
class BacktestJobRepositoryImplTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 9, 7);
    private static final BacktestPeriod PERIOD =
            new BacktestPeriod(LocalDate.of(2019, 1, 1), LocalDate.of(2023, 12, 31));

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
    @DisplayName("save 후 findById로 같은 Job을 읽어온다")
    void saveThenFindById() {
        BacktestJob saved = repository.save(BacktestJob.create("sma-cross", PERIOD));
        entityManager.flush();
        entityManager.clear();

        assertThat(saved.getId()).isNotNull();
        Optional<BacktestJob> found = repository.findById(saved.getId());
        assertThat(found).isPresent();
        assertThat(found.get().getBacktestId()).isEqualTo(saved.getBacktestId());
        assertThat(found.get().getStrategy()).isEqualTo("sma-cross");
        assertThat(found.get().getPeriod()).isEqualTo(PERIOD);
        assertThat(found.get().getStatus()).isEqualTo(BacktestStatus.QUEUED);
    }

    @Test
    @DisplayName("save는 고정 Clock의 날짜와 PK로 backtestId를 부여하고 NOT NULL인 채로 INSERT된다")
    void saveAssignsBacktestIdBeforeInsert() {
        BacktestJob saved = repository.save(BacktestJob.create("sma-cross", PERIOD));
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
        BacktestJob saved = repository.save(BacktestJob.create("sma-cross", PERIOD));
        entityManager.flush();
        entityManager.clear();

        Optional<BacktestJob> found = repository.findByBacktestId(saved.getBacktestId().value());
        assertThat(found).isPresent();
        assertThat(found.get().getId()).isEqualTo(saved.getId());
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
        BacktestJob saved = repository.save(BacktestJob.create("sma-cross", PERIOD));
        entityManager.flush();

        entityManager.persist(
                new BacktestJobJpaEntity(
                        null,
                        saved.getBacktestId().value(),
                        "sma-cross",
                        PERIOD.startDate(),
                        PERIOD.endDate(),
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
                        "sma-cross",
                        PERIOD,
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
                        "sma-cross",
                        PERIOD.startDate(),
                        PERIOD.endDate(),
                        BacktestStatus.QUEUED);

        assertThatThrownBy(() -> entity.assignBacktestIdOn(TODAY))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("BaseEntity의 생성·수정 시각이 Auditing으로 채워진다")
    void auditingFieldsArePopulated() {
        BacktestJob saved = repository.save(BacktestJob.create("sma-cross", PERIOD));
        entityManager.flush();

        BacktestJobJpaEntity entity = entityManager.find(BacktestJobJpaEntity.class, saved.getId());
        assertThat(entity.getCreatedAt()).isNotNull();
        assertThat(entity.getUpdatedAt()).isNotNull();
    }
}
