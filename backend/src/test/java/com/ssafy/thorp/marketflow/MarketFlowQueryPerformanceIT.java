package com.ssafy.thorp.marketflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ssafy.thorp.marketflow.application.port.MacroIndicatorSnapshotPort;
import com.ssafy.thorp.marketflow.domain.model.FieldReferenceSnapshot;
import com.ssafy.thorp.marketflow.domain.model.IndicatorCollectionState;
import com.ssafy.thorp.marketflow.domain.model.IndicatorMetadata;
import com.ssafy.thorp.marketflow.domain.model.IndicatorPathSnapshot;
import com.ssafy.thorp.marketflow.domain.vo.PathPoint;
import com.ssafy.thorp.marketflow.infrastructure.example.ExampleMacroIndicatorSnapshotAdapter;
import com.ssafy.thorp.support.PostgresTestSupport;
import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(
        properties = {
            "spring.mail.username=market-flow-test",
            "spring.mail.password=market-flow-test"
        })
@AutoConfigureMockMvc
@Import(MarketFlowQueryPerformanceIT.LongHistoryFixtureConfig.class)
class MarketFlowQueryPerformanceIT extends PostgresTestSupport {

    private static final int WARM_UP_COUNT = 30;
    private static final int MEASURE_COUNT = 100;

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private LongHistoryPort longHistoryPort;

    @AfterEach
    void removeExampleReadModel() {
        jdbc.update(
                "DELETE FROM market_flow_field_summary WHERE registry_version = ? AND dataset_version = ?",
                "macro-registry-example-v1",
                "macro-dataset-example-2026-09-16");
    }

    @Test
    void recordsP95ForSevenFieldSummaryReadModel() throws Exception {
        assertThat(longHistoryPort.observationCount()).isEqualTo(17 * 120);
        for (int index = 0; index < WARM_UP_COUNT; index++) {
            mockMvc.perform(get("/market-flow/fields")).andExpect(status().isOk());
        }

        List<Long> elapsedNanos = new ArrayList<>();
        for (int index = 0; index < MEASURE_COUNT; index++) {
            long startedAt = System.nanoTime();
            mockMvc.perform(get("/market-flow/fields")).andExpect(status().isOk());
            elapsedNanos.add(System.nanoTime() - startedAt);
        }

        Collections.sort(elapsedNanos);
        double p95Millis = elapsedNanos.get(94) / 1_000_000.0;
        System.out.printf("MARKET_FLOW_FIELDS_P95_MS=%.3f%n", p95Millis);
        assertThat(p95Millis).isLessThan(2_000.0);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class LongHistoryFixtureConfig {

        @Bean
        @Primary
        LongHistoryPort longHistoryPort(ObjectMapper objectMapper) {
            return new LongHistoryPort(new ExampleMacroIndicatorSnapshotAdapter(objectMapper));
        }
    }

    /** 17개 지표마다 120개월을 보유하되 포트 계약대로 기준월의 마지막 세 점만 노출하는 성능 fixture. */
    static final class LongHistoryPort implements MacroIndicatorSnapshotPort {

        private static final int MONTH_COUNT = 120;

        private final ExampleMacroIndicatorSnapshotAdapter delegate;
        private final Map<String, List<PathPoint>> historyByIndicator = new LinkedHashMap<>();

        LongHistoryPort(ExampleMacroIndicatorSnapshotAdapter delegate) {
            this.delegate = delegate;
            FieldReferenceSnapshot references = delegate.loadFieldReferenceMonths();
            for (var field : references.fields()) {
                for (String indicatorId : field.indicatorIds()) {
                    List<PathPoint> history = new ArrayList<>();
                    YearMonth firstMonth = field.referenceMonth().minusMonths(MONTH_COUNT - 1L);
                    for (int index = 0; index < MONTH_COUNT; index++) {
                        history.add(
                                new PathPoint(
                                        firstMonth.plusMonths(index),
                                        BigDecimal.valueOf(index).movePointLeft(1)));
                    }
                    historyByIndicator.put(indicatorId, List.copyOf(history));
                }
            }
        }

        int observationCount() {
            return historyByIndicator.values().stream().mapToInt(List::size).sum();
        }

        @Override
        public FieldReferenceSnapshot loadFieldReferenceMonths() {
            return delegate.loadFieldReferenceMonths();
        }

        @Override
        public Optional<IndicatorPathSnapshot> loadRecentPath(
                String indicatorId, YearMonth referenceMonth) {
            List<PathPoint> history = historyByIndicator.get(indicatorId);
            if (history == null || !history.getLast().month().equals(referenceMonth)) {
                return Optional.empty();
            }
            IndicatorPathSnapshot original =
                    delegate.loadRecentPath(indicatorId, referenceMonth).orElseThrow();
            return Optional.of(
                    new IndicatorPathSnapshot(
                            indicatorId,
                            referenceMonth,
                            history.subList(history.size() - 3, history.size()),
                            original.releaseDate(),
                            original.afterReferenceMonthRelease()));
        }

        @Override
        public List<IndicatorMetadata> loadIndicatorMeta(String registryVersion) {
            return delegate.loadIndicatorMeta(registryVersion);
        }

        @Override
        public List<IndicatorCollectionState> loadCollectionState() {
            return delegate.loadCollectionState();
        }
    }
}
