package com.ssafy.thorp.regime.infrastructure.persistence;

import com.ssafy.thorp.regime.domain.model.CaseDistance;
import com.ssafy.thorp.regime.domain.model.CaseExplanation;
import com.ssafy.thorp.regime.domain.model.CaseIndicatorValue;
import com.ssafy.thorp.regime.domain.model.EraPosition;
import com.ssafy.thorp.regime.domain.model.RegimeCase;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 저장된 사례 행을 도메인 모델로 옮긴다. 값을 만들지 않고 모양만 바꾼다. */
final class RegimeCaseMapper {

    private RegimeCaseMapper() {}

    static RegimeCase toDomain(RegimeCaseJpaEntity entity) {
        return new RegimeCase(
                entity.getRank(),
                JsonReader.toMonth(entity.getEndMonth()),
                distance(entity.getDistance()),
                indicatorValues(entity.getIndicatorValues()),
                explanation(entity.getExplanation()),
                eraPositions(entity.getEraPosition()));
    }

    private static CaseDistance distance(Map<String, Object> source) {
        // contribution 키가 없으면 null 그대로 둔다 — 빈 맵으로 바꾸면 "기여가 0"으로 읽힌다.
        Map<String, Object> contribution =
                source.get("contribution") == null
                        ? null
                        : JsonReader.object(source, "contribution");
        Map<String, Object> blockContribution =
                source.get("blockContribution") == null
                        ? null
                        : JsonReader.object(source, "blockContribution");
        return new CaseDistance(
                JsonReader.decimal(source, "value"),
                decimalMap(JsonReader.object(source, "byField")),
                contribution == null ? null : decimalMap(contribution),
                blocks(source),
                blockContribution == null ? null : decimalMap(blockContribution),
                source.get("meetsThreshold") instanceof Boolean meets ? meets : null);
    }

    @SuppressWarnings("unchecked")
    private static List<CaseDistance.BlockGap> blocks(Map<String, Object> source) {
        // v1 결과에는 키가 없다. 빈 목록이 "블록으로 재지 않았다"와 같은 뜻이다.
        return JsonReader.array(source, "blocks").stream()
                .map(raw -> (Map<String, Object>) raw)
                .map(
                        body ->
                                new CaseDistance.BlockGap(
                                        JsonReader.text(body, "id"),
                                        JsonReader.text(body, "name"),
                                        JsonReader.decimal(body, "gap"),
                                        JsonReader.decimal(body, "spread")))
                .toList();
    }

    private static Map<String, BigDecimal> decimalMap(Map<String, Object> source) {
        Map<String, BigDecimal> values = new LinkedHashMap<>();
        source.forEach((key, value) -> values.put(key, JsonReader.decimal(value)));
        return Map.copyOf(values);
    }

    private static Map<String, CaseIndicatorValue> indicatorValues(Map<String, Object> source) {
        Map<String, CaseIndicatorValue> values = new LinkedHashMap<>();
        source.forEach(
                (indicatorId, raw) -> {
                    Map<String, Object> body = JsonReader.object(source, indicatorId);
                    values.put(
                            indicatorId,
                            new CaseIndicatorValue(
                                    indicatorId,
                                    JsonReader.text(body, "unit"),
                                    JsonReader.months(body, "comparisonMonths"),
                                    JsonReader.months(body, "caseMonths"),
                                    JsonReader.decimals(body, "queryRaw"),
                                    JsonReader.decimals(body, "caseRaw"),
                                    JsonReader.decimals(body, "queryZ"),
                                    JsonReader.decimals(body, "caseZ"),
                                    JsonReader.decimal(body, "e"),
                                    JsonReader.decimal(body, "nominalWeight"),
                                    JsonReader.decimal(body, "distanceShare")));
                });
        return Map.copyOf(values);
    }

    @SuppressWarnings("unchecked")
    private static CaseExplanation explanation(Map<String, Object> source) {
        List<CaseExplanation.ContextLink> context =
                JsonReader.array(source, "context").stream()
                        .map(raw -> (Map<String, Object>) raw)
                        .map(
                                body ->
                                        new CaseExplanation.ContextLink(
                                                JsonReader.text(body, "kind"),
                                                JsonReader.date(body, "date"),
                                                JsonReader.text(body, "title"),
                                                JsonReader.text(body, "url"),
                                                JsonReader.text(body, "source")))
                        .toList();
        return new CaseExplanation(
                strings(source, "similar"),
                strings(source, "different"),
                Boolean.TRUE.equals(source.get("unusual")),
                JsonReader.text(source, "queryNote"),
                context);
    }

    private static List<String> strings(Map<String, Object> source, String key) {
        return JsonReader.array(source, key).stream().map(String::valueOf).toList();
    }

    private static Map<String, EraPosition> eraPositions(Map<String, Object> source) {
        if (source == null || source.isEmpty()) {
            // 전부 만들지 못한 사례다. 빈 맵이 "키가 하나도 없다"와 같은 뜻이라 그대로 둔다.
            return Map.of();
        }
        Map<String, EraPosition> positions = new LinkedHashMap<>();
        source.forEach(
                (indicatorId, raw) -> {
                    Map<String, Object> body = JsonReader.object(source, indicatorId);
                    positions.put(
                            indicatorId,
                            new EraPosition(
                                    indicatorId,
                                    JsonReader.text(body, "sentence"),
                                    JsonReader.text(body, "method"),
                                    JsonReader.month(body, "windowStart"),
                                    JsonReader.month(body, "windowEnd"),
                                    JsonReader.integer(body, "months")));
                });
        return Map.copyOf(positions);
    }
}
