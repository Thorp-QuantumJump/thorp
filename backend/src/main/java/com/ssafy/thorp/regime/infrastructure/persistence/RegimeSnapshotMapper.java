package com.ssafy.thorp.regime.infrastructure.persistence;

import com.ssafy.thorp.regime.domain.model.CandidateCounts;
import com.ssafy.thorp.regime.domain.model.ComparisonMonths;
import com.ssafy.thorp.regime.domain.model.DataAsOf;
import com.ssafy.thorp.regime.domain.model.IndicatorAsOf;
import com.ssafy.thorp.regime.domain.model.IndicatorScale;
import com.ssafy.thorp.regime.domain.model.NewerRelease;
import com.ssafy.thorp.regime.domain.model.RankedCandidate;
import com.ssafy.thorp.regime.domain.model.RegimeCase;
import com.ssafy.thorp.regime.domain.model.RegimeSnapshot;
import com.ssafy.thorp.regime.domain.vo.SearchMode;
import com.ssafy.thorp.regime.domain.vo.SearchStatus;
import com.ssafy.thorp.regime.domain.vo.SnapshotKey;
import com.ssafy.thorp.regime.domain.vo.StatusReason;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 저장된 snapshot 행을 도메인 모델로 옮긴다. <b>값을 만들지 않고 모양만 바꾼다.</b> */
final class RegimeSnapshotMapper {

    private RegimeSnapshotMapper() {}

    static RegimeSnapshot toDomain(RegimeSnapshotJpaEntity entity, List<RegimeCase> cases) {
        return new RegimeSnapshot(
                entity.getId(),
                SnapshotKey.parse(entity.getSnapshotKey()),
                SearchMode.valueOf(entity.getMode()),
                entity.getFieldId(),
                JsonReader.toMonth(entity.getBaselineMonth()),
                entity.getRevision(),
                entity.getDataVersion(),
                entity.getRegistryVersion(),
                entity.getModelVersion(),
                SearchStatus.valueOf(entity.getStatus()),
                entity.getStatusReason() == null
                        ? null
                        : StatusReason.valueOf(entity.getStatusReason()),
                scales(entity.getScale()),
                comparisonMonths(entity.getComparisonMonths()),
                rankedCandidates(entity.getRankedCandidates()),
                candidateCounts(entity.getCandidateCounts()),
                dataAsOf(entity.getDataAsof()),
                entity.getComputedAt(),
                cases);
    }

    private static Map<String, IndicatorScale> scales(Map<String, Object> source) {
        Map<String, IndicatorScale> scales = new LinkedHashMap<>();
        for (String indicatorId : source.keySet()) {
            Map<String, Object> body = JsonReader.object(source, indicatorId);
            scales.put(
                    indicatorId,
                    new IndicatorScale(
                            indicatorId,
                            JsonReader.decimal(body, "m"),
                            JsonReader.decimal(body, "s"),
                            JsonReader.text(body, "method"),
                            JsonReader.month(body, "windowStart"),
                            JsonReader.month(body, "windowEnd"),
                            JsonReader.integer(body, "months")));
        }
        return Map.copyOf(scales);
    }

    private static Map<String, ComparisonMonths> comparisonMonths(Map<String, Object> source) {
        Map<String, ComparisonMonths> months = new LinkedHashMap<>();
        for (String indicatorId : source.keySet()) {
            Map<String, Object> body = JsonReader.object(source, indicatorId);
            months.put(
                    indicatorId,
                    new ComparisonMonths(
                            indicatorId,
                            JsonReader.months(body, "months"),
                            JsonReader.months(body, "omitted")));
        }
        return Map.copyOf(months);
    }

    private static List<RankedCandidate> rankedCandidates(List<Map<String, Object>> source) {
        return source.stream()
                .map(
                        body ->
                                new RankedCandidate(
                                        JsonReader.month(body, "endMonth"),
                                        JsonReader.decimal(body, "distance"),
                                        JsonReader.integer(body, "rank")))
                .toList();
    }

    private static CandidateCounts candidateCounts(Map<String, Object> source) {
        Map<String, Object> excluded = JsonReader.object(source, "excluded");
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String reason : excluded.keySet()) {
            counts.put(reason, JsonReader.integer(excluded, reason));
        }
        return new CandidateCounts(JsonReader.integer(source, "evaluated"), Map.copyOf(counts));
    }

    private static DataAsOf dataAsOf(Map<String, Object> source) {
        Map<String, Object> indicators = JsonReader.object(source, "indicators");
        List<IndicatorAsOf> asOf =
                indicators.keySet().stream()
                        .map(
                                indicatorId -> {
                                    Map<String, Object> body =
                                            JsonReader.object(indicators, indicatorId);
                                    return new IndicatorAsOf(
                                            indicatorId,
                                            JsonReader.month(body, "targetMonth"),
                                            JsonReader.date(body, "publishedAt"),
                                            JsonReader.date(body, "revisedAt"));
                                })
                        .toList();
        List<NewerRelease> newer =
                JsonReader.array(source, "newerReleases").stream()
                        .map(RegimeSnapshotMapper::newerRelease)
                        .toList();
        return new DataAsOf(asOf, newer);
    }

    @SuppressWarnings("unchecked")
    private static NewerRelease newerRelease(Object raw) {
        Map<String, Object> body = (Map<String, Object>) raw;
        return new NewerRelease(
                JsonReader.text(body, "indicatorId"),
                JsonReader.month(body, "month"),
                JsonReader.date(body, "publishedAt"));
    }
}
