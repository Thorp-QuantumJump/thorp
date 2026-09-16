package com.ssafy.thorp.news.support;

import com.ssafy.thorp.news.application.port.NewsMetrics;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 지표 기록 대역. 무엇이 기록됐는지 확인할 수 있게 남긴다.
 *
 * <p>Micrometer 레지스트리를 시험에 끌어들이지 않는다. 확인하려는 것은 "무엇을 셌는가"이지 Micrometer가 그것을 어떻게 저장하는가가 아니다.
 */
public final class RecordingNewsMetrics implements NewsMetrics {

    public final List<String> runs = new ArrayList<>();
    public final List<String> failures = new ArrayList<>();
    public final List<String> retries = new ArrayList<>();
    public final List<String> queries = new ArrayList<>();
    public final List<String> exclusions = new ArrayList<>();
    public final List<java.util.Map<String, Long>> retentionRuns = new ArrayList<>();
    public final java.util.Map<String, java.util.Optional<Duration>> lastSuccessAges =
            new java.util.LinkedHashMap<>();

    @Override
    public void recordIngestRun(
            String sourceKey,
            Duration duration,
            int collected,
            int excluded,
            int duplicates,
            int digests) {
        runs.add(sourceKey + "|" + collected + "|" + excluded + "|" + duplicates + "|" + digests);
    }

    @Override
    public void recordIngestFailure(String sourceKey, String errorCategory) {
        failures.add(sourceKey + "|" + errorCategory);
    }

    @Override
    public void recordRetry(String sourceKey) {
        retries.add(sourceKey);
    }

    @Override
    public void recordExcluded(String sourceKey, String reason) {
        exclusions.add(sourceKey + "|" + reason);
    }

    @Override
    public void recordLastSuccessAge(
            String sourceKey, java.util.Optional<Duration> sinceLastSuccess) {
        lastSuccessAges.put(sourceKey, sinceLastSuccess);
    }

    @Override
    public void recordQuery(String state, Duration duration) {
        queries.add(state);
    }

    @Override
    public void recordRetentionRun(java.util.Map<String, Long> deletedRowsByTable) {
        retentionRuns.add(new java.util.LinkedHashMap<>(deletedRowsByTable));
    }
}
