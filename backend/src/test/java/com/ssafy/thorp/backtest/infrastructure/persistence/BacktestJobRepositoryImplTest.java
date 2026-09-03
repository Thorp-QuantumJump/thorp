package com.ssafy.thorp.backtest.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.ssafy.thorp.backtest.domain.model.BacktestJob;
import com.ssafy.thorp.backtest.domain.model.BacktestStatus;
import com.ssafy.thorp.backtest.domain.vo.BacktestPeriod;
import com.ssafy.thorp.common.config.JpaAuditingConfig;
import java.time.LocalDate;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;

/**
 * H2 라운드트립. {@code @DataJpaTest}는 {@code @Configuration}·{@code @Repository} 구현체를 스캔하지 않으므로 둘을 명시
 * 등록한다. Auditing이 없으면 {@code created_at NOT NULL} 제약에 걸려 저장이 실패한다.
 */
@DataJpaTest
@Import({JpaAuditingConfig.class, BacktestJobRepositoryImpl.class})
class BacktestJobRepositoryImplTest {

    @Autowired private BacktestJobRepositoryImpl repository;
    @Autowired private TestEntityManager entityManager;

    @Test
    @DisplayName("save 후 findById로 같은 Job을 읽어온다")
    void saveThenFindById() {
        BacktestPeriod period =
                new BacktestPeriod(LocalDate.of(2019, 1, 1), LocalDate.of(2023, 12, 31));

        BacktestJob saved = repository.save(BacktestJob.create("sma-cross", period));
        entityManager.flush();
        entityManager.clear();

        assertThat(saved.getId()).isNotNull();
        Optional<BacktestJob> found = repository.findById(saved.getId());
        assertThat(found).isPresent();
        assertThat(found.get().getStrategy()).isEqualTo("sma-cross");
        assertThat(found.get().getPeriod()).isEqualTo(period);
        assertThat(found.get().getStatus()).isEqualTo(BacktestStatus.QUEUED);
    }

    @Test
    @DisplayName("BaseEntity의 생성·수정 시각이 Auditing으로 채워진다")
    void auditingFieldsArePopulated() {
        BacktestJob saved =
                repository.save(
                        BacktestJob.create(
                                "sma-cross",
                                new BacktestPeriod(
                                        LocalDate.of(2019, 1, 1), LocalDate.of(2023, 12, 31))));
        entityManager.flush();

        BacktestJobJpaEntity entity = entityManager.find(BacktestJobJpaEntity.class, saved.getId());
        assertThat(entity.getCreatedAt()).isNotNull();
        assertThat(entity.getUpdatedAt()).isNotNull();
    }

    @Test
    @DisplayName("없는 id는 빈 Optional을 돌려준다")
    void findByIdReturnsEmptyWhenMissing() {
        assertThat(repository.findById(999L)).isEmpty();
    }
}
