package com.ssafy.thorp.paramsearch.domain.model;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 후보 선정 규칙의 스냅샷. <b>접수 시점에 고정하고 이후 바꾸지 않는다</b>(FR-013c, 원칙 VII). 결과를 본 뒤 규칙을 바꾸면 그것은 새 묶음이다.
 *
 * <p><b>절대 문턱 숫자를 담지 않는다</b>(FR-013). 하락 폭 필터는 "동일 비중 기준보다 나쁘지 않다"는 상대 기준이고, 안정성 필터는 "묶음 내 상위 N%"라는
 * 상대 분위다. 어느 쪽도 "MDD 20% 이하" 같은 절대값을 쓰지 않는다.
 *
 * <p><b>JSON 직렬화를 이 클래스가 하지 않는다.</b> 도메인 계층은 Jackson에 의존할 수 없다({@code ArchitectureTest} 규칙). 대신
 * {@link #toMap()}/{@link #fromMap(Map)}으로 순수 {@code Map} 변환만 제공하고, 실제 JSONB 직렬화는 infrastructure의
 * 엔티티 매핑이 한다. 이렇게 두면 저장 형식이 바뀌어도 도메인이 흔들리지 않는다.
 *
 * <p>{@code provisional}은 임시값인 항목 이름이며 화면 표시에 쓴다. 현재 {@code displayCount}(상위 5개)가 여기 들어간다.
 */
public record SelectionRule(
        DrawdownFilter drawdownFilter,
        StabilityFilter stabilityFilter,
        String sortMetric,
        int displayCount,
        String tieHandling,
        StabilityFormula stabilityFormula,
        List<String> provisional) {

    public SelectionRule {
        Objects.requireNonNull(drawdownFilter, "drawdownFilter must not be null");
        Objects.requireNonNull(stabilityFilter, "stabilityFilter must not be null");
        requireText(sortMetric, "sortMetric");
        requireText(tieHandling, "tieHandling");
        Objects.requireNonNull(stabilityFormula, "stabilityFormula must not be null");
        if (displayCount < 1) {
            throw new IllegalArgumentException("displayCount must be positive: " + displayCount);
        }
        provisional =
                List.copyOf(Objects.requireNonNull(provisional, "provisional must not be null"));
    }

    /**
     * 하락 폭 필터. 기준 대비 상대 비교이므로 절대 문턱이 없다(FR-013d).
     *
     * <p>{@code baselineName}은 등록표가 준 표시명이다. 함께 남기는 이유는 {@code searchSpace}가 축 라벨을 남기는 이유와 같다 — 등록표
     * 버전이 올라가 이름이 바뀐 뒤에도 그 묶음이 무엇을 기준으로 삼았는지 읽을 수 있어야 한다(FR-020). 지정이 없거나 옛 스냅샷이면 null이다.
     */
    public record DrawdownFilter(String type, String baseline, String baselineName) {
        public DrawdownFilter {
            requireText(type, "drawdownFilter.type");
            requireText(baseline, "drawdownFilter.baseline");
        }

        public DrawdownFilter(String type, String baseline) {
            this(type, baseline, null);
        }
    }

    /** 안정성 필터. 묶음 내 상대 분위이므로 절대 문턱이 없다(FR-013). */
    public record StabilityFilter(String type, int keepTopPercent) {
        public StabilityFilter {
            requireText(type, "stabilityFilter.type");
            if (keepTopPercent < 1 || keepTopPercent > 100) {
                throw new IllegalArgumentException(
                        "keepTopPercent must be in [1, 100]: " + keepTopPercent);
            }
        }
    }

    /** 인접 안정성 계산식. 이웃 정의와 값 산출 방식을 기록으로 남긴다(FR-020). */
    public record StabilityFormula(String neighbor, String value) {
        public StabilityFormula {
            requireText(neighbor, "stabilityFormula.neighbor");
            requireText(value, "stabilityFormula.value");
        }
    }

    /** 저장·전송용 {@code Map}. 키 순서는 data-model.md §5의 예시와 같다. */
    public Map<String, Object> toMap() {
        Map<String, Object> drawdown = new LinkedHashMap<>();
        drawdown.put("type", drawdownFilter.type());
        drawdown.put("baseline", drawdownFilter.baseline());
        if (drawdownFilter.baselineName() != null) {
            // 값이 없으면 키를 넣지 않는다 — 저장 전후의 맵이 달라지면 스냅샷 비교가 깨진다.
            drawdown.put("baselineName", drawdownFilter.baselineName());
        }

        Map<String, Object> stability = new LinkedHashMap<>();
        stability.put("type", stabilityFilter.type());
        stability.put("keepTopPercent", stabilityFilter.keepTopPercent());

        Map<String, Object> formula = new LinkedHashMap<>();
        formula.put("neighbor", stabilityFormula.neighbor());
        formula.put("value", stabilityFormula.value());

        Map<String, Object> map = new LinkedHashMap<>();
        map.put("drawdownFilter", drawdown);
        map.put("stabilityFilter", stability);
        map.put("sortMetric", sortMetric);
        map.put("displayCount", displayCount);
        map.put("tieHandling", tieHandling);
        map.put("stabilityFormula", formula);
        map.put("provisional", provisional);
        return map;
    }

    /** 저장된 {@code Map}에서 복원한다. 형식이 어긋나면 예외로 드러낸다 — 규칙이 조용히 달라지면 선정 근거를 신뢰할 수 없다. */
    public static SelectionRule fromMap(Map<String, Object> map) {
        Objects.requireNonNull(map, "selectionRule map must not be null");
        Map<String, Object> drawdown = nested(map, "drawdownFilter");
        Map<String, Object> stability = nested(map, "stabilityFilter");
        Map<String, Object> formula = nested(map, "stabilityFormula");
        return new SelectionRule(
                new DrawdownFilter(
                        text(drawdown, "type"),
                        text(drawdown, "baseline"),
                        // 옛 스냅샷에는 없다. 없으면 없는 대로 둔다 — 지어내면 재현 기록이 거짓이 된다.
                        drawdown.get("baselineName") == null
                                ? null
                                : drawdown.get("baselineName").toString()),
                new StabilityFilter(text(stability, "type"), number(stability, "keepTopPercent")),
                text(map, "sortMetric"),
                number(map, "displayCount"),
                text(map, "tieHandling"),
                new StabilityFormula(text(formula, "neighbor"), text(formula, "value")),
                stringList(map, "provisional"));
    }

    /** 이 항목이 임시값인가. 화면이 임시값 표시를 붙일지 판단한다. */
    public boolean isProvisional(String name) {
        return provisional.contains(name);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> nested(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (!(value instanceof Map<?, ?>)) {
            throw new IllegalArgumentException("selectionRule." + key + " must be an object");
        }
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<String> stringList(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value == null) {
            return List.of();
        }
        if (!(value instanceof List<?>)) {
            throw new IllegalArgumentException("selectionRule." + key + " must be an array");
        }
        return List.copyOf((List<String>) value);
    }

    private static String text(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (!(value instanceof String s) || s.isBlank()) {
            throw new IllegalArgumentException(
                    "selectionRule." + key + " must be a non-blank string");
        }
        return s;
    }

    private static int number(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (!(value instanceof Number n)) {
            throw new IllegalArgumentException("selectionRule." + key + " must be a number");
        }
        return n.intValue();
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
