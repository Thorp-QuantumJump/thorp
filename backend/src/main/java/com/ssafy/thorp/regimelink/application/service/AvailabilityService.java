package com.ssafy.thorp.regimelink.application.service;

import com.ssafy.thorp.backtest.application.port.MarketMetadataPort;
import com.ssafy.thorp.common.exception.BusinessException;
import com.ssafy.thorp.common.exception.ErrorCode;
import com.ssafy.thorp.regimelink.application.command.AvailabilityQuery;
import com.ssafy.thorp.regimelink.application.port.BacktestRunReadPort;
import com.ssafy.thorp.regimelink.application.port.BundleRunView;
import com.ssafy.thorp.regimelink.application.port.TradingCalendarPort;
import com.ssafy.thorp.regimelink.application.repository.CaseCandidate;
import com.ssafy.thorp.regimelink.application.repository.RegimeLinkFilterRepository;
import com.ssafy.thorp.regimelink.application.repository.RegimeSearchReadRepository;
import com.ssafy.thorp.regimelink.application.repository.RegimeSearchView;
import com.ssafy.thorp.regimelink.application.result.AvailabilityResult;
import com.ssafy.thorp.regimelink.application.result.CaseWindowResult;
import com.ssafy.thorp.regimelink.domain.model.Availability;
import com.ssafy.thorp.regimelink.domain.model.AvailableRange;
import com.ssafy.thorp.regimelink.domain.model.FilterDefinition;
import com.ssafy.thorp.regimelink.domain.model.InstrumentHistory;
import com.ssafy.thorp.regimelink.domain.model.LinkFilter;
import com.ssafy.thorp.regimelink.domain.model.PostWindow;
import com.ssafy.thorp.regimelink.domain.vo.AvailabilityReason;
import com.ssafy.thorp.regimelink.domain.vo.CaseRef;
import com.ssafy.thorp.regimelink.domain.vo.FilterKey;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 사례마다 비교 묶음을 평가할 수 있는지 판정한다 (research R4, T028·T090).
 *
 * <p><b>이것은 계산이 아니라 메타데이터 조회다.</b> 상장일·워밍업·거래일 달력을 읽어 "그 구간의 값을 낼 수 있는가"만 가린다 — 수익률은 워커가 낸다(원칙 1).
 * 판정은 010 <b>표시 후보(≤10)에만</b> 하고 아래 순위에서 채우지 않는다(Clarifications 09-17 Q4).
 *
 * <p>판정 순서는 research R4 표 그대로다: 대응표 → ETF 이력 → 워밍업 → 사후 자료 → 분석 기간 → 상장폐지 부분. 앞의 넷이 공통 가용 범위 조건이고,
 * {@code OUT_OF_PERIOD}는 <b>필터 조건이 아니다</b> — 원천 자료는 충분하므로 사용자가 확인하면 전체 기간으로 다시 계산할 수 있다.
 */
@Service
public class AvailabilityService {

    private final RegimeSearchReadRepository searches;
    private final BacktestRunReadPort bundles;
    private final TradingCalendarPort calendar;
    private final MarketMetadataPort marketMetadata;
    private final RegimeLinkFilterRepository filters;
    private final FilterKeyCalculator filterKeys;

    public AvailabilityService(
            RegimeSearchReadRepository searches,
            BacktestRunReadPort bundles,
            TradingCalendarPort calendar,
            MarketMetadataPort marketMetadata,
            RegimeLinkFilterRepository filters,
            FilterKeyCalculator filterKeys) {
        this.searches = searches;
        this.bundles = bundles;
        this.calendar = calendar;
        this.marketMetadata = marketMetadata;
        this.filters = filters;
        this.filterKeys = filterKeys;
    }

    /**
     * 표시 후보에 판정을 붙여 돌려준다 (contracts §1).
     *
     * <p>필터 행은 조회에서도 저장한다 — 사용자 식별자가 없는 <b>캐시 성격의 행</b>이고, 같은 묶음·같은 자료면 같은 키라 재사용된다(data-model §2).
     * 사용자에게 귀속되는 기록({@code regime_link_search})은 여기서 만들지 않는다.
     */
    @Transactional
    public AvailabilityResult evaluate(AvailabilityQuery query) {
        RegimeSearchView search = findSearch(query);
        List<BundleRunView> runs = bundles.resolveBundle(query.base(), query.compare());
        Universe universe = universe(runs);
        LocalDate postCutoff = postCutoff(search.baselineMonth());

        boolean filtered = query.entryPath().filtered();
        LinkFilter stored = filtered ? resolveFilter(universe, postCutoff) : null;

        List<AvailabilityResult.CaseAvailability> cases = new ArrayList<>();
        int excluded = 0;
        for (CaseCandidate candidate : search.cases()) {
            AvailabilityResult.CaseAvailability evaluated =
                    evaluateCase(
                            search.snapshotKey(), candidate, runs, universe, postCutoff, filtered);
            cases.add(evaluated);
            if (!evaluated.shownInFiltered()) {
                excluded++;
            }
        }

        return new AvailabilityResult(
                new AvailabilityResult.Search(
                        search.snapshotKey(),
                        search.mode(),
                        search.fieldId(),
                        search.baselineMonth(),
                        search.status()),
                new AvailabilityResult.Bundle(runs, universe.instruments()),
                stored == null
                        ? AvailabilityResult.Filter.notApplied(PostWindow.MIN_TRADING_DAYS)
                        : new AvailabilityResult.Filter(
                                true,
                                stored.key(),
                                stored.availableRange(),
                                excluded,
                                PostWindow.MIN_TRADING_DAYS),
                cases);
    }

    /**
     * 실행 토큰 없이 사례의 사후 구간만 읽는다 (contracts §1a).
     *
     * <p>003이 분석 기간을 고르기 전에 하한을 알아야 해서 있는 조회다. 비교 묶음이 없으므로 판정은 붙지 않는다.
     */
    @Transactional(readOnly = true)
    public CaseWindowResult caseWindow(CaseRef caseRef) {
        RegimeSearchView search =
                searches.findByKey(caseRef.snapshotKey())
                        .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        boolean known =
                search.cases().stream()
                        .anyMatch(found -> found.endMonth().equals(caseRef.endMonth()));
        if (!known) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        }
        return new CaseWindowResult(
                caseRef, window(caseRef.endMonth(), postCutoff(search.baselineMonth())));
    }

    private RegimeSearchView findSearch(AvailabilityQuery query) {
        Optional<RegimeSearchView> found =
                query.pinned()
                        ? searches.findByKey(query.snapshotKey())
                        : searches.findLatest(query.mode(), query.field(), query.baselineMonth());
        return found.orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
    }

    /**
     * 사후 완성 판정의 기준일 (T090, research R4).
     *
     * <p>기본은 자료 마감일이다. 검색의 기준월이 자료 마감월보다 과거이면(사용자가 과거를 가상의 오늘로 두고 찾은 경우, FR-008) 그 기준월의 마지막 거래일을 쓴다
     * — 그때 시점에서 보이지 않았을 사후 자료를 완성으로 세면 그 검색이 현재 기준월의 검색과 같은 목록을 내게 된다.
     */
    private LocalDate postCutoff(YearMonth baselineMonth) {
        LocalDate dataAsOf = calendar.dataAsOf();
        if (baselineMonth == null || !baselineMonth.isBefore(YearMonth.from(dataAsOf))) {
            return dataAsOf;
        }
        return calendar.lastTradingDayOnOrBefore(baselineMonth.atEndOfMonth()).orElse(dataAsOf);
    }

    /** 사례의 사후 구간을 시장 달력으로 옮긴다. 실행별 사정(상장폐지)은 여기 들어오지 않는다. */
    private PostWindow window(YearMonth endMonth, LocalDate postCutoff) {
        LocalDate d0 =
                calendar.firstTradingDayAfter(endMonth.atEndOfMonth())
                        .orElseThrow(
                                () ->
                                        new BusinessException(
                                                ErrorCode.INSUFFICIENT_HISTORY,
                                                "사례 다음 거래일이 달력에 없습니다."));
        int completed =
                Math.min(calendar.tradingDaysBetween(d0, postCutoff), PostWindow.FULL_TRADING_DAYS);
        LocalDate d21 =
                completed >= PostWindow.MIN_TRADING_DAYS
                        ? calendar.nthTradingDayFrom(d0, PostWindow.MIN_TRADING_DAYS).orElse(null)
                        : null;
        LocalDate d63 =
                completed >= PostWindow.FULL_TRADING_DAYS
                        ? calendar.nthTradingDayFrom(d0, PostWindow.FULL_TRADING_DAYS).orElse(null)
                        : null;
        LocalDate lastAvailable =
                completed <= 0 ? null : calendar.nthTradingDayFrom(d0, completed).orElse(null);
        return new PostWindow(d0, d21, d63, lastAvailable, completed, d21 != null, d63 != null);
    }

    private AvailabilityResult.CaseAvailability evaluateCase(
            String snapshotKey,
            CaseCandidate candidate,
            List<BundleRunView> runs,
            Universe universe,
            LocalDate postCutoff,
            boolean filtered) {
        PostWindow window = window(candidate.endMonth(), postCutoff);
        String mappingVersion =
                calendar.mappingVersionAsOf(candidate.endMonth().atEndOfMonth()).orElse(null);

        List<AvailabilityResult.RunAvailability> perRun = new ArrayList<>(runs.size());
        Availability bundle = null;
        for (BundleRunView run : runs) {
            Availability evaluated = evaluateRun(run, window, universe, mappingVersion);
            perRun.add(new AvailabilityResult.RunAvailability(run.publicToken(), evaluated));
            bundle = evaluated.worseOf(bundle);
        }
        Availability resolved =
                bundle == null ? Availability.normal(window.completedTradingDays()) : bundle;

        return new AvailabilityResult.CaseAvailability(
                new CaseRef(snapshotKey, candidate.endMonth()),
                candidate.rank(),
                candidate.distance(),
                window,
                resolved,
                perRun,
                !filtered || !resolved.state().excludedByFilter());
    }

    /** research R4 판정 표를 그 순서대로 적용한다. 한 판정에서 걸리면 뒤는 보지 않는다. */
    private Availability evaluateRun(
            BundleRunView run, PostWindow window, Universe universe, String mappingVersion) {
        LocalDate d0 = window.d0();

        for (BundleRunView.UniverseEntry entry : run.universe()) {
            // ① 대응표 — 사례 관측 종료월의 버전에 그 섹터가 없으면 이 실행만 걸린다.
            // 그 시점에 유효한 버전이 없으면(대응표 이력이 그 앞까지 닿지 않으면) 판정을 건너뛴다.
            // 지금 버전으로 과거를 판정하면 대응표가 바뀐 날 옛 사례가 통째로 사라진다.
            if (mappingVersion != null
                    && entry.sectorId() != null
                    && !calendar.sectorMapped(mappingVersion, entry.sectorId())) {
                return Availability.insufficient(AvailabilityReason.SECTOR_UNMAPPED);
            }
            InstrumentHistory history = universe.history(entry.instrumentId());
            // ② ETF 이력 — 구간 시작에 거래되지 않는 종목이 있으면 그 사례는 계산 자체가 성립하지 않는다.
            if (history == null || !history.tradableOn(d0)) {
                return Availability.insufficient(AvailabilityReason.UNLISTED);
            }
            // ③ 워밍업 — 상장은 했지만 d0 직전까지 그 전략의 워밍업 거래일을 채우지 못하는 경우다.
            LocalDate evaluable =
                    marketMetadata
                            .evaluationStart(history.listedOn(), run.warmupTradingDays())
                            .orElse(null);
            if (evaluable == null || evaluable.isAfter(d0)) {
                return Availability.insufficient(AvailabilityReason.WARMUP_SHORT);
            }
        }

        // ④ 사후 자료 — 구간 안 상장폐지가 있으면 확보 거래일은 폐지 전날까지로 줄어든다.
        LocalDate delisting = earliestDelisting(run, window, universe);
        int completed = window.completedTradingDays();
        if (delisting != null) {
            completed = Math.min(completed, tradingDaysUntilDelisting(d0, delisting));
        }
        if (completed < PostWindow.MIN_TRADING_DAYS) {
            return Availability.incomplete(
                    delisting == null ? AvailabilityReason.RECENT : AvailabilityReason.DELISTED,
                    delisting,
                    completed);
        }

        // ⑤ 분석 기간 — 원천은 충분한데 기존 실행이 이 구간을 담고 있지 않은 경우다.
        LocalDate baselineDate = calendar.lastTradingDayOnOrBefore(d0.minusDays(1)).orElse(null);
        LocalDate coverEnd = calendar.nthTradingDayFrom(d0, completed).orElse(null);
        if (baselineDate == null || !run.covers(baselineDate, coverEnd)) {
            return Availability.outOfPeriod(completed);
        }

        // ⑥ 상장폐지 부분 — 21 이상을 확보했으면 폐지 전날까지의 결과를 낸다.
        return delisting == null
                ? Availability.normal(completed)
                : Availability.partial(delisting, completed);
    }

    /** 이 실행의 종목 가운데 사후 구간 <b>안에서</b> 폐지된 것 중 가장 이른 폐지일. */
    private LocalDate earliestDelisting(BundleRunView run, PostWindow window, Universe universe) {
        LocalDate windowEnd = window.lastAvailable();
        if (windowEnd == null) {
            // 판정 기준일까지 거래일이 하나도 없는 사례다. 구간이 없으므로 그 안의 폐지도 없다 —
            // 여기서 미래의 폐지일을 잡으면 자료가 아직 없는 최근 사례를 DELISTED 로 설명하게 된다.
            return null;
        }
        LocalDate earliest = null;
        for (BundleRunView.UniverseEntry entry : run.universe()) {
            InstrumentHistory history = universe.history(entry.instrumentId());
            if (history == null || history.delistedOn() == null) {
                continue;
            }
            LocalDate delisted = history.delistedOn();
            if (delisted.isAfter(window.d0())
                    && !delisted.isAfter(windowEnd)
                    && (earliest == null || delisted.isBefore(earliest))) {
                earliest = delisted;
            }
        }
        return earliest;
    }

    private int tradingDaysUntilDelisting(LocalDate d0, LocalDate delisting) {
        return calendar.lastTradingDayOnOrBefore(delisting.minusDays(1))
                .map(last -> calendar.tradingDaysBetween(d0, last))
                .orElse(0);
    }

    /**
     * 공통 가용 범위 필터를 만들고 저장한다 (FR-003).
     *
     * <p>정의에 성과가 들어가지 않으므로 같은 묶음·같은 자료면 같은 키다. 키가 이미 있으면 그 행을 그대로 쓴다.
     */
    private LinkFilter resolveFilter(Universe universe, LocalDate postCutoff) {
        int maxWarmup = universe.maxWarmupTradingDays();
        FilterDefinition definition =
                new FilterDefinition(
                        universe.instruments(),
                        maxWarmup,
                        PostWindow.MIN_TRADING_DAYS,
                        universe.mappingVersion(marketMetadata),
                        marketMetadata.currentDataVersion(),
                        calendar.dataAsOf(),
                        postCutoff);
        FilterKey key = filterKeys.calculate(definition);
        return filters.findByKey(key)
                .orElseGet(
                        () ->
                                filters.save(
                                        LinkFilter.create(
                                                key,
                                                definition,
                                                availableRange(
                                                        universe.instruments(),
                                                        maxWarmup,
                                                        postCutoff))));
    }

    /**
     * 선택한 ETF로 비교할 수 있는 사례 종료월의 범위.
     *
     * <p>시작은 모든 종목이 상장되고 워밍업을 채운 뒤라야 하고, 끝은 그 뒤로 21거래일을 확보할 수 있어야 한다. 폐지된 종목이 있으면 폐지 전날이 끝의 상한이다 —
     * 그 뒤에 시작하는 사례는 그 종목이 처음부터 없다.
     */
    private AvailableRange availableRange(
            List<InstrumentHistory> instruments, int maxWarmup, LocalDate postCutoff) {
        if (instruments.isEmpty()) {
            return AvailableRange.empty();
        }
        LocalDate firstEvaluable = null;
        LocalDate horizon = postCutoff;
        for (InstrumentHistory instrument : instruments) {
            LocalDate evaluable =
                    marketMetadata.evaluationStart(instrument.listedOn(), maxWarmup).orElse(null);
            if (evaluable == null) {
                return AvailableRange.empty();
            }
            if (firstEvaluable == null || evaluable.isAfter(firstEvaluable)) {
                firstEvaluable = evaluable;
            }
            if (instrument.delistedOn() != null) {
                LocalDate before =
                        calendar.lastTradingDayOnOrBefore(instrument.delistedOn().minusDays(1))
                                .orElse(null);
                if (before == null) {
                    return AvailableRange.empty();
                }
                if (before.isBefore(horizon)) {
                    horizon = before;
                }
            }
        }
        YearMonth first = firstMonthAfter(firstEvaluable);
        YearMonth last = lastMonthWithMinimum(horizon);
        if (first == null || last == null || first.isAfter(last)) {
            return AvailableRange.empty();
        }
        return new AvailableRange(first, last);
    }

    /** {@code d0}가 {@code evaluable} 이후가 되는 가장 이른 관측 종료월. */
    private YearMonth firstMonthAfter(LocalDate evaluable) {
        YearMonth month = YearMonth.from(evaluable);
        YearMonth previous = month.minusMonths(1);
        LocalDate d0 = calendar.firstTradingDayAfter(previous.atEndOfMonth()).orElse(null);
        return d0 != null && !d0.isBefore(evaluable) ? previous : month;
    }

    /**
     * {@code horizon}까지 21거래일을 확보할 수 있는 가장 늦은 관측 종료월.
     *
     * <p>뒤에서부터 달을 하나씩 내려 본다 — 21거래일은 한 달 남짓이라 몇 번 만에 끝나고, 열두 달을 넘기면 자료가 없는 것으로 본다.
     */
    private YearMonth lastMonthWithMinimum(LocalDate horizon) {
        YearMonth month = YearMonth.from(horizon);
        for (int back = 0; back < 12; back++) {
            LocalDate d0 = calendar.firstTradingDayAfter(month.atEndOfMonth()).orElse(null);
            if (d0 != null && !d0.isAfter(horizon)) {
                LocalDate d21 =
                        calendar.nthTradingDayFrom(d0, PostWindow.MIN_TRADING_DAYS).orElse(null);
                if (d21 != null && !d21.isAfter(horizon)) {
                    return month;
                }
            }
            month = month.minusMonths(1);
        }
        return null;
    }

    /** 비교 묶음이 쓰는 종목 전체와 그 이력. 실행마다 겹치는 종목은 한 번만 읽는다. */
    private Universe universe(List<BundleRunView> runs) {
        Map<String, InstrumentHistory> histories = new LinkedHashMap<>();
        Set<String> unknown = new LinkedHashSet<>();
        int maxWarmup = 0;
        String mappingVersion = null;
        for (BundleRunView run : runs) {
            maxWarmup = Math.max(maxWarmup, run.warmupTradingDays());
            if (mappingVersion == null) {
                mappingVersion = run.mappingVersion();
            }
            for (BundleRunView.UniverseEntry entry : run.universe()) {
                String instrumentId = entry.instrumentId();
                if (histories.containsKey(instrumentId) || unknown.contains(instrumentId)) {
                    continue;
                }
                InstrumentHistory history = history(entry.sectorId(), instrumentId);
                if (history == null) {
                    unknown.add(instrumentId);
                } else {
                    histories.put(instrumentId, history);
                }
            }
        }
        return new Universe(histories, maxWarmup, mappingVersion);
    }

    /**
     * 종목 하나의 이력. 상장일을 모르면 {@code null}이고, 그 종목을 쓴 실행은 {@code UNLISTED}가 된다.
     *
     * <p>001 표에 없는 종목은 004 접수가 이미 막지만, 그 뒤에 자료가 바뀌는 경우가 있으므로 판정을 내보내는 쪽을 택한다 — 조회가 500으로 끝나면 화면은 아무
     * 사례도 보이지 못한다.
     */
    private InstrumentHistory history(String sectorId, String instrumentId) {
        return marketMetadata
                .listedOn(instrumentId)
                .map(
                        listedOn ->
                                new InstrumentHistory(
                                        sectorId == null ? instrumentId : sectorId,
                                        instrumentId,
                                        listedOn,
                                        calendar.delistedOn(instrumentId).orElse(null)))
                .orElse(null);
    }

    /**
     * 판정에 쓰는 묶음 전체의 입력. 실행을 넘나들며 같은 값을 다시 읽지 않으려고 한 번 모은다.
     *
     * <p>이력을 모르는 종목은 여기 없다 — 그 종목을 쓴 실행은 {@code UNLISTED}가 되므로 조회에 실패하지 않는다.
     */
    private record Universe(
            Map<String, InstrumentHistory> histories,
            int maxWarmupTradingDays,
            String mappingVersion) {

        /** 종목 이력을 {@code instrumentId} 순으로. 필터 정의와 응답이 같은 순서를 보게 한다. */
        List<InstrumentHistory> instruments() {
            return histories.values().stream()
                    .sorted(Comparator.comparing(InstrumentHistory::instrumentId))
                    .toList();
        }

        InstrumentHistory history(String instrumentId) {
            return histories.get(instrumentId);
        }

        /** 실행에 기록이 없으면 지금 열린 대응표 버전을 쓴다. 필터 정의에는 버전 문자열이 반드시 있어야 한다. */
        String mappingVersion(MarketMetadataPort marketMetadata) {
            return mappingVersion == null ? marketMetadata.currentMappingVersion() : mappingVersion;
        }
    }
}
