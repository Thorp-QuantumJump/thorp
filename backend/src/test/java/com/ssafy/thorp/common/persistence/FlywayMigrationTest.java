package com.ssafy.thorp.common.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.ssafy.thorp.backtest.domain.model.BacktestJob;
import com.ssafy.thorp.backtest.domain.model.BacktestStatus;
import com.ssafy.thorp.backtest.domain.vo.BacktestPeriod;
import com.ssafy.thorp.backtest.infrastructure.persistence.BacktestJobRepositoryImpl;
import com.ssafy.thorp.common.config.JpaAuditingConfig;
import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

/**
 * 운영(prod)과 같은 조건으로 V1 마이그레이션을 검증한다. Flyway가 스키마를 만들고 Hibernate는 {@code ddl-auto=validate}로 검증만
 * 하므로, 마이그레이션과 Entity 매핑이 어긋나면 컨텍스트 로딩 자체가 실패한다.
 *
 * <p>DB는 H2다. PostgreSQL 전용 문법을 쓰면 여기서 걸린다.
 */
@DataJpaTest
@Import({JpaAuditingConfig.class, BacktestJobRepositoryImpl.class})
@TestPropertySource(
        properties = {
            "spring.flyway.enabled=true",
            "spring.flyway.locations=classpath:db/migration",
            "spring.flyway.baseline-on-migrate=false",
            "spring.jpa.hibernate.ddl-auto=validate"
        })
class FlywayMigrationTest {

    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private BacktestJobRepositoryImpl repository;
    @Autowired private TestEntityManager entityManager;

    @Test
    @DisplayName("V1이 빈 DB에 적용되고 flyway_schema_history에 성공으로 기록된다")
    void v1IsAppliedToEmptyDatabase() {
        Integer applied =
                jdbcTemplate.queryForObject(
                        // Flyway는 이력 테이블과 컬럼 이름을 소문자로 따옴표 처리해 만든다.
                        // 기본 식별자를 대문자로 올리는 H2에서는 따옴표를 붙여야 찾을 수 있다.
                        "SELECT COUNT(*) FROM \"flyway_schema_history\""
                                + " WHERE \"version\" = '1' AND \"success\" = TRUE",
                        Integer.class);

        assertThat(applied).isEqualTo(1);
    }

    @Test
    @DisplayName("V1이 만든 backtest_job은 컬럼 7개를 그대로 가진다")
    void v1CreatesExpectedColumns() {
        var columns =
                jdbcTemplate.queryForList(
                        "SELECT LOWER(column_name) FROM information_schema.columns"
                                + " WHERE LOWER(table_name) = 'backtest_job'",
                        String.class);

        assertThat(columns)
                .containsExactlyInAnyOrder(
                        "id",
                        "strategy",
                        "start_date",
                        "end_date",
                        "status",
                        "created_at",
                        "updated_at");
    }

    @Test
    @DisplayName("V1 스키마에 Entity를 저장하고 다시 읽을 수 있다")
    void entityRoundTripsOnMigratedSchema() {
        BacktestPeriod period =
                new BacktestPeriod(LocalDate.of(2019, 1, 1), LocalDate.of(2023, 12, 31));

        BacktestJob saved = repository.save(BacktestJob.create("sma-cross", period));
        entityManager.flush();
        entityManager.clear();

        assertThat(saved.getId()).isNotNull();
        assertThat(repository.findById(saved.getId()))
                .get()
                .satisfies(
                        job -> {
                            assertThat(job.getStrategy()).isEqualTo("sma-cross");
                            assertThat(job.getPeriod()).isEqualTo(period);
                            assertThat(job.getStatus()).isEqualTo(BacktestStatus.QUEUED);
                        });
    }
}
