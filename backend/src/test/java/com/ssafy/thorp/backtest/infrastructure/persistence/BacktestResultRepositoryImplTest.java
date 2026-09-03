package com.ssafy.thorp.backtest.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ssafy.thorp.backtest.BacktestResultFixtures;
import com.ssafy.thorp.backtest.domain.model.BacktestResult;
import com.ssafy.thorp.backtest.domain.vo.EquityPoint;
import com.ssafy.thorp.backtest.domain.vo.PerformanceSummary;
import com.ssafy.thorp.common.config.JpaAuditingConfig;
import jakarta.persistence.PersistenceException;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * H2 라운드트립. {@code equity_curve} JSON 컬럼({@code @JdbcTypeCode(SqlTypes.JSON)})이 H2에서 저장·복원되는지 확정하는
 * 테스트다(specs/003 research.md §1). 실패하면 TEXT + 수동 직렬화로 후퇴한다.
 */
@DataJpaTest
@Import({JpaAuditingConfig.class, BacktestResultRepositoryImpl.class})
class BacktestResultRepositoryImplTest {

    @Autowired private BacktestResultRepositoryImpl repository;
    @Autowired private TestEntityManager entityManager;

    @Test
    @DisplayName("save 후 findByBacktestJobId로 summary 14필드와 시계열 20포인트가 그대로 복원된다")
    void saveThenFindByBacktestJobId() {
        BacktestResult saved = repository.save(BacktestResultFixtures.sampleResult(1L));
        entityManager.flush();
        entityManager.clear();

        assertThat(saved.getId()).isNotNull();
        Optional<BacktestResult> found = repository.findByBacktestJobId(1L);
        assertThat(found).isPresent();

        PerformanceSummary summary = found.get().getSummary();
        PerformanceSummary expected = BacktestResultFixtures.sampleSummary();
        assertThat(summary).isEqualTo(expected);
        assertThat(summary.winRate()).isNull();
        assertThat(summary.profitFactor()).isNull();
        assertThat(summary.maxDrawdownPeakDate()).isEqualTo(LocalDate.of(2020, 1, 1));
        assertThat(summary.maxDrawdownRecoveryDate()).isEqualTo(LocalDate.of(2020, 7, 1));

        List<EquityPoint> points = found.get().getEquityCurve().points();
        assertThat(points).hasSize(20);
        assertThat(points).isEqualTo(BacktestResultFixtures.samplePoints());
        assertThat(points.get(0).equity()).isEqualTo(BacktestResultFixtures.INITIAL_EQUITY);
        assertThat(points.get(19).equity()).isEqualTo(BacktestResultFixtures.FINAL_EQUITY);
        assertThat(points.get(5).drawdown())
                .isCloseTo(104.0 / 119.0 - 1, org.assertj.core.data.Offset.offset(1e-12));
    }

    @Test
    @DisplayName("같은 backtestJobId로 두 번 저장하면 unique 제약에 걸린다")
    void rejectsDuplicateJobId() {
        repository.save(BacktestResultFixtures.sampleResult(2L));
        entityManager.flush();

        assertThatThrownBy(
                        () -> {
                            repository.save(BacktestResultFixtures.sampleResult(2L));
                            entityManager.flush();
                        })
                .isInstanceOfAny(DataIntegrityViolationException.class, PersistenceException.class);
    }

    @Test
    @DisplayName("없는 jobId는 빈 Optional을 돌려준다")
    void findByBacktestJobIdReturnsEmptyWhenMissing() {
        assertThat(repository.findByBacktestJobId(999L)).isEmpty();
    }

    @Test
    @DisplayName("BaseEntity의 생성·수정 시각이 Auditing으로 채워진다")
    void auditingFieldsArePopulated() {
        BacktestResult saved = repository.save(BacktestResultFixtures.sampleResult(3L));
        entityManager.flush();

        BacktestResultJpaEntity entity =
                entityManager.find(BacktestResultJpaEntity.class, saved.getId());
        assertThat(entity.getCreatedAt()).isNotNull();
        assertThat(entity.getUpdatedAt()).isNotNull();
        assertThat(entity.getEquityCurve()).hasSize(20);
    }
}
