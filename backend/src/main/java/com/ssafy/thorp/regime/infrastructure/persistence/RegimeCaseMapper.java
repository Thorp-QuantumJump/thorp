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
        return new CaseDistance(
                JsonReader.decimal(source, "value"),
                decimalMap(JsonReader.object(source, "byField")),
                contribution == null ? null : decimalMap(contribution));
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

    private static CaseExplanation explanation(Map<String, Object> source) {
        return new CaseExplanation(strings(source, "similar"), strings(source, "different"));
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
