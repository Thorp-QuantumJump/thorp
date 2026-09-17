package com.ssafy.thorp.regime.presentation.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.ssafy.thorp.common.json.PlainBigDecimalSerializer;
import com.ssafy.thorp.regime.application.port.IndicatorRegistration;
import com.ssafy.thorp.regime.application.result.RegimeSearchResult;
import com.ssafy.thorp.regime.domain.model.ComparisonMonths;
import com.ssafy.thorp.regime.domain.model.IndicatorAsOf;
import com.ssafy.thorp.regime.domain.model.NewerRelease;
import com.ssafy.thorp.regime.domain.model.RegimeCase;
import com.ssafy.thorp.regime.domain.model.RegimeSnapshot;
import com.ssafy.thorp.regime.domain.vo.SearchMode;
import com.ssafy.thorp.regime.domain.vo.SearchStatus;
import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * 검색 하나의 응답 (계약 §1).
 *
 * <p><b>계산 불가도 이 모양으로 200이다.</b> {@code dataAsOf}·{@code versions}·{@code basis}가 그대로 들어간다 —
 * SC-007이 "검색 결과 화면의 100%"에 대상월과 발표일을, SC-009가 역사 비교 표시를 요구하므로 여기서 빠지면 계산 불가 화면만 100%에서 빠진다.
 *
 * <p>decimal은 필드마다 {@link PlainBigDecimalSerializer}로 문자열이 된다. <b>웹 ObjectMapper에 전역 등록하지 않는다</b> —
 * 다른 도메인의 응답이 숫자에서 문자열로 바뀌어 그 계약이 깨진다.
 */
public record RegimeSearchResponse(
        String searchId,
        SearchMode mode,
        FieldView field,
        String baselineMonth,
        String baselineOrigin,
        List<String> observationMonths,
        List<ComparisonMonthView> comparisonMonths,
        SearchStatus status,
        String statusReason,
        String statusNote,
        String caseLabel,
        String displayLimitNote,
        BasisView basis,
        DataAsOfView dataAsOf,
        VersionsView versions,
        CountsView counts,
        List<CaseSummaryView> cases) {

    public static RegimeSearchResponse from(RegimeSearchResult result) {
        RegimeSnapshot snapshot = result.snapshot();
        Map<String, IndicatorRegistration> names = result.indicators();
        boolean displayLimited = snapshot.eligibleCount() > snapshot.storedCount();
        return new RegimeSearchResponse(
                snapshot.key().toString(),
                snapshot.mode(),
                FieldView.of(result),
                RegimeFormat.month(snapshot.baselineMonth()),
                result.baselineOrigin().name(),
                observationMonths(snapshot),
                comparisonMonths(snapshot, names),
                snapshot.status(),
                snapshot.statusReason() == null ? null : snapshot.statusReason().name(),
                RegimeNotes.statusNote(snapshot.status(), snapshot.statusReason()),
                // 문턱 교정 전에만 붙는다. THRESHOLD_APPLIED 가 되면 사라진다(spec FR-020).
                snapshot.status() == SearchStatus.NEAREST_ONLY ? RegimeNotes.CASE_LABEL : null,
                displayLimited ? RegimeNotes.DISPLAY_LIMIT_NOTE : null,
                new BasisView(RegimeNotes.BASIS_KIND, RegimeNotes.BASIS_NOTE),
                DataAsOfView.of(result),
                VersionsView.of(snapshot),
                CountsView.of(result),
                result.displayedCases().stream()
                        .map(item -> CaseSummaryView.of(item, snapshot))
                        .toList());
    }

    /** 질의가 실제로 본 달 — 지표별 비교 시점의 합집합이다(spec FR-001). */
    private static List<String> observationMonths(RegimeSnapshot snapshot) {
        return snapshot.comparisonMonths().values().stream()
                .flatMap(months -> months.months().stream())
                .distinct()
                .sorted()
                .map(RegimeFormat::month)
                .toList();
    }

    private static List<ComparisonMonthView> comparisonMonths(
            RegimeSnapshot snapshot, Map<String, IndicatorRegistration> names) {
        return snapshot.comparisonMonths().values().stream()
                .sorted(Comparator.comparing(ComparisonMonths::indicatorId))
                .map(months -> ComparisonMonthView.of(months, names.get(months.indicatorId())))
                .toList();
    }

    /** 분야 검색이면 id·이름, 종합이면 {@code null}이다. */
    public record FieldView(String id, String name) {
        static FieldView of(RegimeSearchResult result) {
            return result.fieldRegistration()
                    .map(field -> new FieldView(field.id(), field.name()))
                    .orElse(null);
        }
    }

    /**
     * 지표 하나의 비교 시점 (spec FR-001·FR-031).
     *
     * <p>{@code omitted}가 비어 있지 않으면 {@code note}가 붙는다 — 화면은 이것을 <b>숨길 수 없는 자리에</b> 표시한다(SC-007). 남은
     * 시점으로 계산했다는 사실이 보이지 않으면 세 달로 비교한 결과와 구분되지 않는다.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ComparisonMonthView(
            String indicatorId,
            String indicator,
            List<String> months,
            List<String> omitted,
            String note) {
        static ComparisonMonthView of(ComparisonMonths months, IndicatorRegistration name) {
            String label = name == null ? null : name.name();
            return new ComparisonMonthView(
                    months.indicatorId(),
                    label,
                    months.months().stream().map(RegimeFormat::month).toList(),
                    months.omitted().stream().map(RegimeFormat::month).toList(),
                    RegimeNotes.omittedNote(label, months.omitted()));
        }
    }

    /** 역사 비교 표시 (spec FR-027). 화면이 반드시 노출한다. */
    public record BasisView(String kind, String note) {}

    /** 자료 기준 시점 (spec FR-031). 계산 불가 응답에도 있다. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record DataAsOfView(
            List<IndicatorAsOfView> indicators,
            String note,
            List<RevisionView> revisions,
            List<NewerReleaseView> newerReleases) {

        static DataAsOfView of(RegimeSearchResult result) {
            Map<String, IndicatorRegistration> names = result.indicators();
            List<IndicatorAsOf> asOf = result.snapshot().dataAsOf().indicators();
            return new DataAsOfView(
                    asOf.stream().map(item -> IndicatorAsOfView.of(item, names)).toList(),
                    RegimeNotes.dataAsOfNote(
                            result.fieldRegistration().map(field -> field.name()).orElse(null),
                            latestTargetMonth(asOf)),
                    result.snapshot().dataAsOf().revisions().stream()
                            .map(item -> RevisionView.of(item, names))
                            .toList(),
                    result.snapshot().dataAsOf().newerReleases().stream()
                            .map(item -> NewerReleaseView.of(item, names))
                            .toList());
        }

        private static YearMonth latestTargetMonth(List<IndicatorAsOf> asOf) {
            return asOf.stream()
                    .map(IndicatorAsOf::targetMonth)
                    .max(Comparator.naturalOrder())
                    .orElse(null);
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record IndicatorAsOfView(
            String indicatorId, String indicator, String targetMonth, String publishedAt) {
        static IndicatorAsOfView of(IndicatorAsOf item, Map<String, IndicatorRegistration> names) {
            return new IndicatorAsOfView(
                    item.indicatorId(),
                    RegimeFormat.name(names, item.indicatorId()),
                    RegimeFormat.month(item.targetMonth()),
                    RegimeFormat.date(item.publishedAt()));
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record RevisionView(
            String indicatorId, String indicator, String month, String revisedAt) {
        static RevisionView of(IndicatorAsOf item, Map<String, IndicatorRegistration> names) {
            return new RevisionView(
                    item.indicatorId(),
                    RegimeFormat.name(names, item.indicatorId()),
                    RegimeFormat.month(item.targetMonth()),
                    RegimeFormat.date(item.revisedAt()));
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record NewerReleaseView(
            String indicatorId, String indicator, String month, String publishedAt) {
        static NewerReleaseView of(NewerRelease item, Map<String, IndicatorRegistration> names) {
            return new NewerReleaseView(
                    item.indicatorId(),
                    RegimeFormat.name(names, item.indicatorId()),
                    RegimeFormat.month(item.month()),
                    RegimeFormat.date(item.publishedAt()));
        }
    }

    /**
     * 버전 셋과 예시 자료 표시 (data-model §6.1).
     *
     * <p>{@code sampleData}는 {@code data} 버전의 {@code FIXTURE-} 접두사로 판정한다 — <b>008에 묻지 않는다</b>.
     */
    public record VersionsView(
            String data,
            String registry,
            String model,
            int revision,
            boolean sampleData,
            boolean sampleRegistry) {
        static VersionsView of(RegimeSnapshot snapshot) {
            return new VersionsView(
                    snapshot.dataVersion(),
                    snapshot.registryVersion(),
                    snapshot.modelVersion(),
                    snapshot.revision(),
                    snapshot.sampleData(),
                    snapshot.sampleRegistry());
        }
    }

    /**
     * 후보 집계 (계약 §1).
     *
     * <p>{@code stored > displayed}면 '더 보기'가 남아 있고, {@code eligible > stored}면 표시 상한에 걸린 것이다. 화면은
     * 상한 안내만 보이고 <b>{@code evaluated}·{@code eligible} 값 자체를 내보이지 않는다</b>(spec FR-013, SC-004).
     */
    public record CountsView(int evaluated, int eligible, int stored, int displayed) {
        static CountsView of(RegimeSearchResult result) {
            RegimeSnapshot snapshot = result.snapshot();
            return new CountsView(
                    snapshot.candidateCounts().evaluated(),
                    snapshot.eligibleCount(),
                    snapshot.storedCount(),
                    result.displayedCount());
        }
    }

    /**
     * 목록에 보이는 사례 한 건.
     *
     * <p><b>관측 구간·비슷한 점·다른 점이 먼저다</b> — 거리·기여는 상세로 넘긴다(spec FR-015).
     */
    public record CaseSummaryView(
            String endMonth,
            int rank,
            List<String> observationMonths,
            @JsonSerialize(using = PlainBigDecimalSerializer.class) BigDecimal distance,
            List<String> similar,
            List<String> different,
            boolean detailAvailable) {

        static CaseSummaryView of(RegimeCase item, RegimeSnapshot snapshot) {
            return new CaseSummaryView(
                    RegimeFormat.month(item.endMonth()),
                    item.rank(),
                    observationMonths(item),
                    item.distance().value(),
                    item.explanation().similar(),
                    item.explanation().different(),
                    true);
        }

        private static List<String> observationMonths(RegimeCase item) {
            return item.indicatorValues().values().stream()
                    .flatMap(value -> value.caseMonths().stream())
                    .distinct()
                    .sorted()
                    .map(RegimeFormat::month)
                    .toList();
        }
    }
}
