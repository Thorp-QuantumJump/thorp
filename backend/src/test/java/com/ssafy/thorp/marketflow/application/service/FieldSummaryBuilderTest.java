package com.ssafy.thorp.marketflow.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.json.JsonMapper;
import com.ssafy.thorp.marketflow.application.repository.MarketFlowFieldSummaryRepository;
import com.ssafy.thorp.marketflow.application.result.MarketFlowSnapshot;
import com.ssafy.thorp.marketflow.domain.model.MarketFlowField;
import com.ssafy.thorp.marketflow.domain.model.NarrativeFormatter;
import com.ssafy.thorp.marketflow.domain.model.NarrativeSubjectSelector;
import com.ssafy.thorp.marketflow.infrastructure.example.ExampleMacroIndicatorSnapshotAdapter;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class FieldSummaryBuilderTest {

    @Test
    void sameVersionsProduceTheSameImmutableSevenFieldSnapshot() {
        var port = new ExampleMacroIndicatorSnapshotAdapter(JsonMapper.builder().build());
        var repository = new InMemoryRepository();
        var builder =
                new FieldSummaryBuilder(
                        port,
                        repository,
                        new IndicatorDisplayAssembler(port),
                        new NarrativeSubjectSelector(),
                        new NarrativeFormatter(),
                        new FieldStatusDecider(),
                        Clock.fixed(Instant.parse("2026-09-16T02:00:00Z"), ZoneOffset.UTC));

        var first = builder.ensureCurrentSnapshot();
        var second = builder.ensureCurrentSnapshot();

        assertThat(first.fields()).hasSize(7).isEqualTo(second.fields());
        assertThat(first.fields())
                .allSatisfy(
                        field -> {
                            assertThat(field.status().name()).isEqualTo("EXAMPLE");
                            assertThat(field.narrative()).doesNotContain("좋다", "나쁘다", "추천", "전망");
                        });
        assertThat(first.fields())
                .filteredOn(field -> field.fieldId().value().equals("production"))
                .singleElement()
                .extracting(MarketFlowField::referenceMonthHeldBackBy)
                .asList()
                .containsExactly("service_production");
        assertThat(repository.saveAttempts).isEqualTo(1);
    }

    /**
     * {@code ensureCurrentSnapshot()}의 synchronized가 실제로 한 번의 적재만 남기는지 본다.
     *
     * <p>이 경계가 풀리면 여러 요청이 같은 버전의 읽기 모델을 동시에 만들어 중복 적재하거나, 절반만 만들어진 목록을 서로 덮어쓴다. 같은 버전이면 같은 결과가 나와야
     * 한다는 원칙 VIII이 그 순간 깨진다.
     */
    @Test
    void concurrentCallsBuildAndPersistExactlyOneSnapshot() throws Exception {
        var port = new ExampleMacroIndicatorSnapshotAdapter(JsonMapper.builder().build());
        var repository = new InMemoryRepository();
        var builder =
                new FieldSummaryBuilder(
                        port,
                        repository,
                        new IndicatorDisplayAssembler(port),
                        new NarrativeSubjectSelector(),
                        new NarrativeFormatter(),
                        new FieldStatusDecider(),
                        Clock.fixed(Instant.parse("2026-09-16T02:00:00Z"), ZoneOffset.UTC));

        int threads = 16;
        var startTogether = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(threads);
        try {
            List<Future<MarketFlowSnapshot>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(
                        executor.submit(
                                () -> {
                                    startTogether.await();
                                    return builder.ensureCurrentSnapshot();
                                }));
            }
            startTogether.countDown();

            List<MarketFlowSnapshot> snapshots = new ArrayList<>();
            for (Future<MarketFlowSnapshot> future : futures) {
                snapshots.add(future.get(30, TimeUnit.SECONDS));
            }

            assertThat(snapshots).hasSize(threads);
            assertThat(snapshots)
                    .allSatisfy(
                            snapshot -> {
                                assertThat(snapshot.fields()).hasSize(7);
                                assertThat(snapshot.fields()).isEqualTo(snapshots.get(0).fields());
                            });
            assertThat(repository.saveAttempts).isEqualTo(1);
        } finally {
            executor.shutdownNow();
        }
    }

    private static final class InMemoryRepository implements MarketFlowFieldSummaryRepository {
        private List<MarketFlowField> fields = List.of();
        private String registryVersion;
        private String datasetVersion;
        private int saveAttempts;

        @Override
        public void saveIfAbsent(
                String registryVersion,
                String datasetVersion,
                List<MarketFlowField> fields,
                Instant builtAt) {
            saveAttempts++;
            if (this.fields.isEmpty()) {
                this.registryVersion = registryVersion;
                this.datasetVersion = datasetVersion;
                this.fields = new ArrayList<>(fields);
            }
        }

        @Override
        public List<MarketFlowField> findAll(String registryVersion, String datasetVersion) {
            if (registryVersion.equals(this.registryVersion)
                    && datasetVersion.equals(this.datasetVersion)) {
                return List.copyOf(fields);
            }
            return List.of();
        }
    }
}
