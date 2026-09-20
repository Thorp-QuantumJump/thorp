package com.ssafy.thorp.regimelink.presentation.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.ssafy.thorp.common.json.PlainBigDecimalSerializer;
import com.ssafy.thorp.regimelink.application.port.BundleRunView;
import com.ssafy.thorp.regimelink.application.result.AvailabilityResult;
import com.ssafy.thorp.regimelink.domain.model.Availability;
import com.ssafy.thorp.regimelink.domain.model.InstrumentHistory;
import com.ssafy.thorp.regimelink.domain.model.PostWindow;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Locale;

/**
 * 가용성 판정 응답 (contracts §1).
 *
 * <p><b>유지율({@code retention})과 초과수익은 이 응답에 없다.</b> 담을 자리를 만들지 않는 것으로 막는다 — 유지율은 기록 전용이고(FR-021)
 * 초과수익은 011이 내보내지 않는 값이다.
 *
 * <p>소수는 문자열로 나간다. 전역 직렬화 설정을 바꾸지 않고 필드마다 지정하는 것은, 전역으로 걸면 다른 도메인의 계약(숫자로 나가는 자리)이 깨지기 때문이다.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AvailabilityResponse(
        Basis basis, Search search, Bundle bundle, Filter filter, List<Case> cases) {

    /** 역사 탐색의 근거 표기. 화면은 이 값을 읽어 "최신 개정자료를 이용한 역사 탐색"을 보인다(FR-015). */
    public record Basis(String kind) {

        static Basis historicalRevision() {
            return new Basis("HISTORICAL_REVISION");
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Search(
            String snapshotKey, String mode, String field, String baselineMonth, String status) {}

    public record Bundle(List<Run> runs, List<Instrument> instruments) {}

    /** 비교 묶음의 실행 하나. 내부 PK 대신 공개 토큰으로 가리킨다. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Run(
            String publicToken,
            String role,
            String strategyId,
            String strategyName,
            Period period,
            int warmupTradingDays) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Period(LocalDate start, LocalDate end, LocalDate effectiveStart) {}

    /** {@code delistedOn}은 폐지된 종목에만 있다 — 키가 없으면 상장이 유지되고 있다는 뜻이다. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Instrument(
            String sectorId, String instrumentId, LocalDate listedOn, LocalDate delistedOn) {}

    /** {@code MARKET_FLOW} 경로면 {@code applied=false}이고 {@code minPostTradingDays}만 남는다. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Filter(
            boolean applied,
            String filterKey,
            Range availableRange,
            Integer excludedCount,
            int minPostTradingDays) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Range(String firstCaseEndMonth, String lastCaseEndMonth) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Case(
            String caseId,
            String endMonth,
            int rank,
            @JsonSerialize(using = PlainBigDecimalSerializer.class) BigDecimal distance,
            Window postWindow,
            State availability,
            List<RunState> perRun,
            boolean shownInFiltered) {}

    /**
     * 사례 목록에 붙는 구간 (contracts §1 예시).
     *
     * <p>{@code d21}·{@code d63}은 판정 기준일을 넘어가면 명시적 {@code null}이다 — 화면이 미완성을 그 자리에서 읽는다.
     *
     * <p>{@code lastAvailable}은 여기 없다. 목록은 사례마다 열 개가 오는 자리라 화면이 쓰지 않는 값을 싣지 않고, 그 값이 필요한 §1a 는
     * {@link CaseWindowResponse} 의 모양을 따로 갖는다.
     */
    public record Window(
            LocalDate d0,
            LocalDate d21,
            LocalDate d63,
            int completedTradingDays,
            boolean complete21,
            boolean complete63) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record State(String state, String reason, Boolean partial, LocalDate delistedOn) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record RunState(
            String publicToken,
            String state,
            String reason,
            Boolean partial,
            LocalDate delistedOn,
            int completedTradingDays) {}

    public static AvailabilityResponse from(AvailabilityResult result) {
        return new AvailabilityResponse(
                Basis.historicalRevision(),
                search(result.search()),
                bundle(result.bundle()),
                filter(result.filter()),
                result.cases().stream().map(AvailabilityResponse::toCase).toList());
    }

    private static Search search(AvailabilityResult.Search search) {
        return new Search(
                search.snapshotKey(),
                // 화면 표기는 소문자다(010 계약 §1과 같은 모양).
                search.mode() == null ? null : search.mode().toLowerCase(Locale.ROOT),
                search.fieldId(),
                month(search.baselineMonth()),
                search.status());
    }

    private static Bundle bundle(AvailabilityResult.Bundle bundle) {
        return new Bundle(
                bundle.runs().stream().map(AvailabilityResponse::run).toList(),
                bundle.instruments().stream().map(AvailabilityResponse::instrument).toList());
    }

    private static Run run(BundleRunView run) {
        return new Run(
                run.publicToken(),
                run.role().name(),
                run.strategyId(),
                run.strategyName(),
                new Period(run.start(), run.end(), run.effectiveStart()),
                run.warmupTradingDays());
    }

    private static Instrument instrument(InstrumentHistory instrument) {
        return new Instrument(
                instrument.sectorId(),
                instrument.instrumentId(),
                instrument.listedOn(),
                instrument.delistedOn());
    }

    private static Filter filter(AvailabilityResult.Filter filter) {
        return new Filter(
                filter.applied(),
                filter.key() == null ? null : filter.key().value(),
                filter.availableRange() == null || filter.availableRange().isEmpty()
                        ? null
                        : new Range(
                                month(filter.availableRange().firstCaseEndMonth()),
                                month(filter.availableRange().lastCaseEndMonth())),
                filter.excludedCount(),
                filter.minPostTradingDays());
    }

    private static Case toCase(AvailabilityResult.CaseAvailability found) {
        return new Case(
                found.caseRef().toString(),
                month(found.endMonth()),
                found.rank(),
                found.distance(),
                window(found.postWindow()),
                state(found.availability()),
                found.perRun().stream()
                        .map(run -> runState(run.publicToken(), run.availability()))
                        .toList(),
                found.shownInFiltered());
    }

    private static Window window(PostWindow window) {
        return new Window(
                window.d0(),
                window.d21(),
                window.d63(),
                window.completedTradingDays(),
                window.complete21(),
                window.complete63());
    }

    private static State state(Availability availability) {
        return new State(
                availability.state().name(),
                availability.reason() == null ? null : availability.reason().name(),
                availability.partial() ? Boolean.TRUE : null,
                availability.delistedOn());
    }

    private static RunState runState(String publicToken, Availability availability) {
        return new RunState(
                publicToken,
                availability.state().name(),
                availability.reason() == null ? null : availability.reason().name(),
                availability.partial() ? Boolean.TRUE : null,
                availability.delistedOn(),
                availability.completedTradingDays());
    }

    private static String month(YearMonth month) {
        return month == null ? null : month.toString();
    }
}
