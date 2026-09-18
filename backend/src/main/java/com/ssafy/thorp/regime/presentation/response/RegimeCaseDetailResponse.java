package com.ssafy.thorp.regime.presentation.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.ssafy.thorp.common.json.PlainBigDecimalSerializer;
import com.ssafy.thorp.regime.application.port.IndicatorRegistration;
import com.ssafy.thorp.regime.application.result.RegimeCaseResult;
import com.ssafy.thorp.regime.domain.model.CaseIndicatorValue;
import com.ssafy.thorp.regime.domain.model.EraPosition;
import com.ssafy.thorp.regime.domain.model.IndicatorScale;
import com.ssafy.thorp.regime.domain.model.RegimeCase;
import com.ssafy.thorp.regime.domain.model.RegimeSnapshot;
import java.math.BigDecimal;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 사례 상세 (계약 §3).
 *
 * <p>여기 담긴 <b>공통 척도와 비교 시점이 SC-003의 수기 대조 입력</b>이다 — 중앙값·산포·방법·기준 구간·비교 시점이 전부 있어 독립 계산으로 z와 {@code
 * E_i}를 다시 만들 수 있다(spec FR-028).
 *
 * <p>{@code eraPosition}은 <b>문장만</b> 준다. 시대별 z 값·분위수·백분율을 넣지 않는다 — 숫자로 놓이면 유사도처럼
 * 읽힌다(FR-017·FR-022). 이력이 부족하거나 보조 척도가 0인 지표는 <b>키가 없고</b>, 전부 만들 수 없으면 {@code null}이다. 0으로 채우지
 * 않는다.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RegimeCaseDetailResponse(
        String searchId,
        String endMonth,
        List<String> observationMonths,
        @JsonSerialize(using = PlainBigDecimalSerializer.class) BigDecimal distance,
        Map<String, String> byField,
        Map<String, String> contribution,
        List<IndicatorDetailView> indicators,
        Map<String, EraPositionView> eraPosition,
        PostPeriodView postPeriod) {

    public static RegimeCaseDetailResponse from(RegimeCaseResult result) {
        RegimeCase item = result.regimeCase();
        RegimeSnapshot snapshot = result.snapshot();
        return new RegimeCaseDetailResponse(
                snapshot.key().toString(),
                RegimeFormat.month(item.endMonth()),
                observationMonths(item),
                item.distance().value(),
                plain(item.distance().byField()),
                // mode=field 에서는 키 자체가 없다 — 분야가 하나뿐이라 값이 늘 1이고,
                // "기여도 100%"로 읽히면 뜻 없는 숫자가 하나 는다(계약 §3).
                item.distance().contribution() == null
                        ? null
                        : plain(item.distance().contribution()),
                indicators(result),
                eraPositions(item),
                new PostPeriodView(RegimeFormat.month(item.endMonth()), false, POST_PERIOD_NOTE));
    }

    /** 011이 붙기 전까지 사후 평가 구간은 없다. <b>빈 값을 만들어 내지 않는다</b>(계약 §3). */
    private static final String POST_PERIOD_NOTE = "사례 이후 결과는 아직 연결되지 않았습니다.";

    private static List<String> observationMonths(RegimeCase item) {
        return item.indicatorValues().values().stream()
                .flatMap(value -> value.caseMonths().stream())
                .distinct()
                .sorted()
                .map(RegimeFormat::month)
                .toList();
    }

    private static Map<String, String> plain(Map<String, BigDecimal> source) {
        Map<String, String> values = new LinkedHashMap<>();
        source.forEach((key, value) -> values.put(key, value.toPlainString()));
        return Map.copyOf(values);
    }

    private static List<IndicatorDetailView> indicators(RegimeCaseResult result) {
        RegimeSnapshot snapshot = result.snapshot();
        return result.regimeCase().indicatorValues().values().stream()
                .sorted(Comparator.comparing(CaseIndicatorValue::indicatorId))
                .map(
                        value ->
                                IndicatorDetailView.of(
                                        value,
                                        result.indicators(),
                                        snapshot.scales().get(value.indicatorId())))
                .toList();
    }

    private static Map<String, EraPositionView> eraPositions(RegimeCase item) {
        if (item.eraPositions().isEmpty()) {
            return null;
        }
        Map<String, EraPositionView> positions = new LinkedHashMap<>();
        item.eraPositions()
                .forEach((id, position) -> positions.put(id, EraPositionView.of(position)));
        return Map.copyOf(positions);
    }

    /**
     * 지표 하나의 상세.
     *
     * <p>비교 시점이 여럿이라 값도 시점 수만큼의 배열이다. 하나로 줄이면 {@code E_i}를 다시 만들 수 없다(SC-003).
     *
     * <p>{@code nominalWeight}(명목 비중)와 {@code distanceShare}(실제 거리 기여)를 <b>나란히</b> 낸다 — 둘의 차이가 U02
     * 검증의 관측 대상이다(spec FR-010). 분야 안 {@code ΣE_i}가 0이면 {@code distanceShare} 키가 없다.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record IndicatorDetailView(
            String id,
            String name,
            String unit,
            List<String> comparisonMonths,
            List<String> caseMonths,
            List<String> queryRaw,
            List<String> caseRaw,
            List<String> queryZ,
            List<String> caseZ,
            @JsonSerialize(using = PlainBigDecimalSerializer.class) BigDecimal e,
            @JsonSerialize(using = PlainBigDecimalSerializer.class) BigDecimal nominalWeight,
            @JsonSerialize(using = PlainBigDecimalSerializer.class) BigDecimal distanceShare,
            ScaleView scale) {

        static IndicatorDetailView of(
                CaseIndicatorValue value,
                Map<String, IndicatorRegistration> names,
                IndicatorScale scale) {
            return new IndicatorDetailView(
                    value.indicatorId(),
                    RegimeFormat.name(names, value.indicatorId()),
                    // 단위는 저장된 값이 먼저다 — 계산 당시의 단위여야 값과 짝이 맞는다.
                    value.unit() != null
                            ? value.unit()
                            : RegimeFormat.unit(names, value.indicatorId()),
                    value.comparisonMonths().stream().map(RegimeFormat::month).toList(),
                    value.caseMonths().stream().map(RegimeFormat::month).toList(),
                    plain(value.queryRaw()),
                    plain(value.caseRaw()),
                    plain(value.queryZ()),
                    plain(value.caseZ()),
                    value.e(),
                    value.nominalWeight(),
                    value.distanceShare(),
                    ScaleView.of(scale));
        }

        private static List<String> plain(List<BigDecimal> values) {
            return values.stream().map(BigDecimal::toPlainString).toList();
        }
    }

    /** 공통 척도 — 수기 대조 입력이다(spec FR-028). */
    public record ScaleView(
            String median,
            String spread,
            String method,
            String windowStart,
            String windowEnd,
            int months) {
        static ScaleView of(IndicatorScale scale) {
            if (scale == null) {
                return null;
            }
            return new ScaleView(
                    scale.median().toPlainString(),
                    scale.spread().toPlainString(),
                    scale.method(),
                    RegimeFormat.month(scale.windowStart()),
                    RegimeFormat.month(scale.windowEnd()),
                    scale.months());
        }
    }

    /** 시대별 보조 위치 — <b>문장만</b> 나간다(spec FR-017). */
    public record EraPositionView(
            String sentence, String method, String windowStart, String windowEnd, int months) {
        static EraPositionView of(EraPosition position) {
            return new EraPositionView(
                    position.sentence(),
                    position.method(),
                    RegimeFormat.month(position.windowStart()),
                    RegimeFormat.month(position.windowEnd()),
                    position.months());
        }
    }

    /** 사후 평가 구간. 011이 붙기 전까지 {@code available=false}다. */
    public record PostPeriodView(String endMonth, boolean available, String note) {}
}
