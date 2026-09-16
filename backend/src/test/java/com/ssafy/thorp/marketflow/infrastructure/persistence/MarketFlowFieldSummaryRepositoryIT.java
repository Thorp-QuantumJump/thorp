package com.ssafy.thorp.marketflow.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.ssafy.thorp.marketflow.domain.model.MarketFlowField;
import com.ssafy.thorp.marketflow.domain.vo.FieldId;
import com.ssafy.thorp.marketflow.domain.vo.FieldStatus;
import com.ssafy.thorp.support.PostgresTestSupport;
import java.time.Instant;
import java.time.YearMonth;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

@DataJpaTest
@Import(MarketFlowFieldSummaryRepositoryImpl.class)
class MarketFlowFieldSummaryRepositoryIT extends PostgresTestSupport {

    private static final String REGISTRY_VERSION = "macro-registry-example-v1";
    private static final String DATASET_VERSION = "macro-dataset-example-2026-09-16";

    @Autowired private MarketFlowFieldSummaryRepositoryImpl repository;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void repeatedSaveOfSameVersionKeepsOneImmutableRow() {
        MarketFlowField original = field("첫 번째 요약");
        repository.saveIfAbsent(
                REGISTRY_VERSION,
                DATASET_VERSION,
                List.of(original),
                Instant.parse("2026-09-16T02:00:00Z"));
        repository.saveIfAbsent(
                REGISTRY_VERSION,
                DATASET_VERSION,
                List.of(field("같은 버전에 뒤늦게 들어온 다른 요약")),
                Instant.parse("2026-09-16T03:00:00Z"));

        Integer count =
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM market_flow_field_summary"
                                + " WHERE field_id = ? AND registry_version = ? AND dataset_version = ?",
                        Integer.class,
                        "production",
                        REGISTRY_VERSION,
                        DATASET_VERSION);
        assertThat(count).isOne();
        assertThat(repository.findAll(REGISTRY_VERSION, DATASET_VERSION))
                .singleElement()
                .extracting(MarketFlowField::narrative)
                .isEqualTo("첫 번째 요약");
    }

    private static MarketFlowField field(String narrative) {
        return new MarketFlowField(
                FieldId.PRODUCTION,
                "생산",
                1,
                FieldStatus.EXAMPLE,
                narrative,
                "market-flow-narrative-v1",
                List.of("mining_manufacturing_production"),
                YearMonth.of(2026, 7),
                List.of("service_production"),
                Instant.parse("2026-09-16T02:00:00Z"),
                true,
                List.of());
    }
}
