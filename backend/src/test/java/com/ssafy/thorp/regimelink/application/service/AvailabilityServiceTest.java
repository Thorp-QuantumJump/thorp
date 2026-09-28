package com.ssafy.thorp.regimelink.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.ssafy.thorp.regimelink.application.command.AvailabilityQuery;
import com.ssafy.thorp.regimelink.application.port.BacktestRunReadPort;
import com.ssafy.thorp.regimelink.application.port.BundleRunView;
import com.ssafy.thorp.regimelink.application.result.AvailabilityResult;
import com.ssafy.thorp.regimelink.domain.vo.AvailabilityReason;
import com.ssafy.thorp.regimelink.domain.vo.AvailabilityState;
import com.ssafy.thorp.regimelink.domain.vo.EntryPath;
import com.ssafy.thorp.regimelink.domain.vo.LinkRole;
import com.ssafy.thorp.regimelink.support.FakeBacktestRuns;
import com.ssafy.thorp.regimelink.support.FakeMarketMetadata;
import com.ssafy.thorp.regimelink.support.FakeRegimeSearches;
import com.ssafy.thorp.regimelink.support.FakeTradingCalendar;
import com.ssafy.thorp.regimelink.support.InMemoryFilterRepository;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 가용성 판정 규칙 (T028·T090, research R4 판정 표).
 *
 * <p>달력은 평일이 거래일인 가짜다. 여기서 확인하는 것은 판정 규칙이지 달력 자료가 아니다 — 실제 달력 조회는 통합 시험이 본다.
 */
class AvailabilityServiceTest {

    private static final String SNAPSHOT_KEY = "FIELD:LABOR:2026-07-01:DATA-1:REG-1:MODEL-1";
    private static final String SEMICONDUCTOR = "091160.XKRX";
    private static final String BIO = "244580.XKRX";
    private static final LocalDate DATA_AS_OF = LocalDate.of(2026, 7, 15);

    private final FakeMarketMetadata metadata = new FakeMarketMetadata();
    private final FakeRegimeSearches searches = new FakeRegimeSearches();
    private final InMemoryFilterRepository filters = new InMemoryFilterRepository();

    @Test
    @DisplayName("원천은 2010년부터인데 기존 실행이 2022년부터면 2018년 사례는 기간 밖이고 기본 목록에 남는다")
    void 기간_밖은_필터로_빼지_않는다() {
        metadata.listed(SEMICONDUCTOR, LocalDate.of(2010, 1, 4));
        searches.holding(
                FakeRegimeSearches.view(
                        SNAPSHOT_KEY,
                        YearMonth.of(2026, 7),
                        "NEAREST_ONLY",
                        YearMonth.of(2018, 8)));

        AvailabilityResult result =
                service(calendar(), run(LocalDate.of(2022, 1, 3), LocalDate.of(2025, 12, 30)))
                        .evaluate(query(EntryPath.STRATEGY_RESULT));

        AvailabilityResult.CaseAvailability found = result.cases().get(0);
        assertThat(found.availability().state()).isEqualTo(AvailabilityState.OUT_OF_PERIOD);
        assertThat(found.shownInFiltered()).isTrue();
        assertThat(result.filter().excludedCount()).isZero();
    }

    @Test
    @DisplayName("원천 워밍업이 모자라면 기간 밖과 달리 자료 부족이고 기본 목록에서 빠진다")
    void 워밍업_부족은_자료_부족이다() {
        // 상장이 사례 직전이라 63거래일 워밍업을 채우지 못한다.
        metadata.listed(SEMICONDUCTOR, LocalDate.of(2018, 8, 1));
        searches.holding(
                FakeRegimeSearches.view(
                        SNAPSHOT_KEY,
                        YearMonth.of(2026, 7),
                        "NEAREST_ONLY",
                        YearMonth.of(2018, 8)));

        AvailabilityResult result =
                service(calendar(), run(LocalDate.of(2010, 1, 4), LocalDate.of(2025, 12, 30)))
                        .evaluate(query(EntryPath.STRATEGY_RESULT));

        AvailabilityResult.CaseAvailability found = result.cases().get(0);
        assertThat(found.availability().state()).isEqualTo(AvailabilityState.DATA_INSUFFICIENT);
        assertThat(found.availability().reason()).isEqualTo(AvailabilityReason.WARMUP_SHORT);
        assertThat(found.shownInFiltered()).isFalse();
        assertThat(result.filter().excludedCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("사후 21거래일을 채우지 못한 최근 사례는 제외 수에 들고 요약 수치가 없다")
    void 최근_사례는_미완성이다() {
        metadata.listed(SEMICONDUCTOR, LocalDate.of(2010, 1, 4));
        searches.holding(
                FakeRegimeSearches.view(
                        SNAPSHOT_KEY,
                        YearMonth.of(2026, 7),
                        "NEAREST_ONLY",
                        YearMonth.of(2026, 6)));

        AvailabilityResult result =
                service(calendar(), run(LocalDate.of(2010, 1, 4), LocalDate.of(2026, 7, 15)))
                        .evaluate(query(EntryPath.STRATEGY_RESULT));

        AvailabilityResult.CaseAvailability found = result.cases().get(0);
        assertThat(found.availability().state()).isEqualTo(AvailabilityState.POST_INCOMPLETE);
        assertThat(found.availability().reason()).isEqualTo(AvailabilityReason.RECENT);
        assertThat(found.postWindow().completedTradingDays()).isLessThan(21);
        assertThat(found.postWindow().d21()).isNull();
        assertThat(found.shownInFiltered()).isFalse();
        assertThat(result.filter().excludedCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("구간 10거래일째 상장폐지면 묶음 전체가 미완성이다")
    void 이른_상장폐지는_미완성이다() {
        metadata.listed(SEMICONDUCTOR, LocalDate.of(2010, 1, 4))
                .listed(BIO, LocalDate.of(2010, 1, 4));
        searches.holding(
                FakeRegimeSearches.view(
                        SNAPSHOT_KEY,
                        YearMonth.of(2026, 7),
                        "NEAREST_ONLY",
                        YearMonth.of(2018, 8)));
        // d0 = 2018-09-03(월). 그로부터 10거래일째가 2018-09-14 이고, 그 전날까지는 9거래일이다.
        FakeTradingCalendar calendar = calendar().delisted(BIO, LocalDate.of(2018, 9, 14));

        AvailabilityResult result =
                service(
                                calendar,
                                // 비교 묶음의 두 실행은 같은 섹터 후보를 쓴다 — 다르면 조건 대조(T093)가
                                // 계산 전에 400 으로 막는다. 그래서 종목 이력에서 오는 사유는 두 실행에
                                // 함께 걸리고, 실행마다 갈리는 것은 워밍업·분석 기간 쪽이다.
                                run(
                                        "base",
                                        LinkRole.BASE,
                                        1L,
                                        List.of(
                                                entry("SEMICONDUCTOR", SEMICONDUCTOR),
                                                entry("BIO", BIO)),
                                        LocalDate.of(2010, 1, 4),
                                        LocalDate.of(2025, 12, 30)),
                                run(
                                        "compare",
                                        LinkRole.COMPARE,
                                        2L,
                                        List.of(
                                                entry("SEMICONDUCTOR", SEMICONDUCTOR),
                                                entry("BIO", BIO)),
                                        LocalDate.of(2010, 1, 4),
                                        LocalDate.of(2025, 12, 30)))
                        .evaluate(query(EntryPath.STRATEGY_RESULT));

        AvailabilityResult.CaseAvailability found = result.cases().get(0);
        assertThat(found.perRun().get(0).availability().state())
                .isEqualTo(AvailabilityState.POST_INCOMPLETE);
        assertThat(found.perRun().get(1).availability().state())
                .isEqualTo(AvailabilityState.POST_INCOMPLETE);
        assertThat(found.perRun().get(1).availability().reason())
                .isEqualTo(AvailabilityReason.DELISTED);
        // 묶음 판정은 가장 나쁜 실행을 따른다.
        assertThat(found.availability().state()).isEqualTo(AvailabilityState.POST_INCOMPLETE);
        assertThat(found.shownInFiltered()).isFalse();
        assertThat(result.filter().excludedCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("구간 40거래일째 상장폐지면 정상이되 부분 결과로 표시하고 확보 거래일이 40이다")
    void 늦은_상장폐지는_부분_결과다() {
        metadata.listed(BIO, LocalDate.of(2010, 1, 4));
        searches.holding(
                FakeRegimeSearches.view(
                        SNAPSHOT_KEY,
                        YearMonth.of(2026, 7),
                        "NEAREST_ONLY",
                        YearMonth.of(2018, 8)));
        // d0 = 2018-09-03. 41번째 거래일에 폐지되면 전날까지 40거래일을 확보한다.
        LocalDate delisting =
                new FakeTradingCalendar(DATA_AS_OF)
                        .nthTradingDayFrom(LocalDate.of(2018, 9, 3), 41)
                        .orElseThrow();
        FakeTradingCalendar calendar = calendar().delisted(BIO, delisting);

        AvailabilityResult result =
                service(
                                calendar,
                                run(
                                        "base",
                                        LinkRole.BASE,
                                        1L,
                                        List.of(entry("BIO", BIO)),
                                        LocalDate.of(2010, 1, 4),
                                        LocalDate.of(2025, 12, 30)))
                        .evaluate(query(EntryPath.STRATEGY_RESULT));

        AvailabilityResult.CaseAvailability found = result.cases().get(0);
        assertThat(found.availability().state()).isEqualTo(AvailabilityState.NORMAL);
        assertThat(found.availability().partial()).isTrue();
        assertThat(found.availability().delistedOn()).isEqualTo(delisting);
        assertThat(found.availability().completedTradingDays()).isEqualTo(40);
        assertThat(found.shownInFiltered()).isTrue();
    }

    @Test
    @DisplayName("사례 시점의 대응표에 없는 섹터를 쓰면 자료 부족이 된다")
    void 대응표에_없는_섹터는_그_실행만_걸린다() {
        metadata.listed(SEMICONDUCTOR, LocalDate.of(2010, 1, 4))
                .listed(BIO, LocalDate.of(2010, 1, 4));
        searches.holding(
                FakeRegimeSearches.view(
                        SNAPSHOT_KEY,
                        YearMonth.of(2026, 7),
                        "NEAREST_ONLY",
                        YearMonth.of(2018, 8)));
        FakeTradingCalendar calendar = calendar().mapping("MAP-2018", "SEMICONDUCTOR");

        AvailabilityResult result =
                service(
                                calendar,
                                run(
                                        "base",
                                        LinkRole.BASE,
                                        1L,
                                        List.of(
                                                entry("SEMICONDUCTOR", SEMICONDUCTOR),
                                                entry("BIO", BIO)),
                                        LocalDate.of(2010, 1, 4),
                                        LocalDate.of(2025, 12, 30)),
                                run(
                                        "compare",
                                        LinkRole.COMPARE,
                                        2L,
                                        List.of(
                                                entry("SEMICONDUCTOR", SEMICONDUCTOR),
                                                entry("BIO", BIO)),
                                        LocalDate.of(2010, 1, 4),
                                        LocalDate.of(2025, 12, 30)))
                        .evaluate(query(EntryPath.STRATEGY_RESULT));

        AvailabilityResult.CaseAvailability found = result.cases().get(0);
        assertThat(found.perRun().get(0).availability().reason())
                .isEqualTo(AvailabilityReason.SECTOR_UNMAPPED);
        assertThat(found.perRun().get(1).availability().reason())
                .isEqualTo(AvailabilityReason.SECTOR_UNMAPPED);
    }

    @Test
    @DisplayName("사례 시점에 유효한 대응표가 없으면 대응표 판정을 건너뛴다 — 지금 버전으로 과거를 판정하지 않는다 (T109)")
    void 대응표_이력이_닿지_않으면_건너뛴다() {
        metadata.listed(SEMICONDUCTOR, LocalDate.of(2010, 1, 4))
                .listed(BIO, LocalDate.of(2010, 1, 4));
        searches.holding(
                FakeRegimeSearches.view(
                        SNAPSHOT_KEY,
                        YearMonth.of(2026, 7),
                        "NEAREST_ONLY",
                        YearMonth.of(2018, 8)));
        // 지금 버전에는 BIO 가 없다. 그 버전으로 2018 사례를 판정하면 SECTOR_UNMAPPED 로 사라진다.
        FakeTradingCalendar calendar =
                calendar()
                        .mapping("MAP-2026", "SEMICONDUCTOR")
                        .mappingSince(LocalDate.of(2026, 9, 1));

        AvailabilityResult result =
                service(
                                calendar,
                                run(
                                        "base",
                                        LinkRole.BASE,
                                        1L,
                                        List.of(
                                                entry("SEMICONDUCTOR", SEMICONDUCTOR),
                                                entry("BIO", BIO)),
                                        LocalDate.of(2010, 1, 4),
                                        LocalDate.of(2025, 12, 30)))
                        .evaluate(query(EntryPath.STRATEGY_RESULT));

        assertThat(result.cases().get(0).perRun().get(0).availability().reason())
                .isNotEqualTo(AvailabilityReason.SECTOR_UNMAPPED);
    }

    @Test
    @DisplayName("취소돼 일찍 끊긴 실행은 그 뒤 구간을 덮지 않는다")
    void 취소된_실행은_구간을_덮지_않는다() {
        metadata.listed(SEMICONDUCTOR, LocalDate.of(2010, 1, 4));
        searches.holding(
                FakeRegimeSearches.view(
                        SNAPSHOT_KEY,
                        YearMonth.of(2026, 7),
                        "NEAREST_ONLY",
                        YearMonth.of(2018, 8)));
        // 요청 기간은 사례를 덮지만 실제로 돈 것은 사후 구간이 시작되기도 전까지다.
        BundleRunView cancelled =
                run(
                        "9f2c1a7b4e0d83526af1c9d0b7e34512",
                        LinkRole.BASE,
                        1L,
                        List.of(entry("SEMICONDUCTOR", SEMICONDUCTOR)),
                        LocalDate.of(2010, 1, 4),
                        LocalDate.of(2025, 12, 30),
                        LocalDate.of(2018, 8, 31));

        AvailabilityResult result =
                service(calendar(), cancelled).evaluate(query(EntryPath.STRATEGY_RESULT));

        assertThat(result.cases().get(0).availability().state())
                .isEqualTo(AvailabilityState.OUT_OF_PERIOD);
        // 원천 자료는 충분하므로 기본 목록에는 남는다.
        assertThat(result.cases().get(0).shownInFiltered()).isTrue();
    }

    @Test
    @DisplayName("대응표와 이력이 함께 걸리면 종목 순서와 상관없이 대응표가 사유다")
    void 판정_순서는_종목_순서를_타지_않는다() {
        metadata.listed(SEMICONDUCTOR, LocalDate.of(2010, 1, 4))
                .listed(BIO, LocalDate.of(2020, 1, 2));
        searches.holding(
                FakeRegimeSearches.view(
                        SNAPSHOT_KEY,
                        YearMonth.of(2026, 7),
                        "NEAREST_ONLY",
                        YearMonth.of(2018, 8)));
        // BIO 는 사례 시점에 아직 상장 전이고, SEMICONDUCTOR 는 그때 대응표에 없다.
        FakeTradingCalendar calendar = calendar().mapping("MAP-2018", "BIO");

        AvailabilityResult unmappedFirst =
                service(
                                calendar,
                                run(
                                        "base",
                                        LinkRole.BASE,
                                        1L,
                                        List.of(
                                                entry("SEMICONDUCTOR", SEMICONDUCTOR),
                                                entry("BIO", BIO)),
                                        LocalDate.of(2010, 1, 4),
                                        LocalDate.of(2025, 12, 30)))
                        .evaluate(query(EntryPath.STRATEGY_RESULT));
        AvailabilityResult unlistedFirst =
                service(
                                calendar,
                                run(
                                        "base",
                                        LinkRole.BASE,
                                        1L,
                                        List.of(
                                                entry("BIO", BIO),
                                                entry("SEMICONDUCTOR", SEMICONDUCTOR)),
                                        LocalDate.of(2010, 1, 4),
                                        LocalDate.of(2025, 12, 30)))
                        .evaluate(query(EntryPath.STRATEGY_RESULT));

        // research R4 는 단계 순서를 정했다 — 대응표를 먼저 보므로 종목을 어느 쪽으로 적든 같은 사유다.
        assertThat(unmappedFirst.cases().get(0).availability().reason())
                .isEqualTo(AvailabilityReason.SECTOR_UNMAPPED);
        assertThat(unlistedFirst.cases().get(0).availability().reason())
                .isEqualTo(AvailabilityReason.SECTOR_UNMAPPED);
    }

    @Test
    @DisplayName("기존 실행의 분석 기간이 달라도 필터 키는 같다")
    void 필터_키에_실행_기간이_들어가지_않는다() {
        metadata.listed(SEMICONDUCTOR, LocalDate.of(2010, 1, 4));
        searches.holding(
                FakeRegimeSearches.view(
                        SNAPSHOT_KEY,
                        YearMonth.of(2026, 7),
                        "NEAREST_ONLY",
                        YearMonth.of(2018, 8)));

        AvailabilityResult shorter =
                service(calendar(), run(LocalDate.of(2015, 1, 2), LocalDate.of(2025, 12, 30)))
                        .evaluate(query(EntryPath.STRATEGY_RESULT));
        AvailabilityResult longer =
                service(calendar(), run(LocalDate.of(2010, 1, 4), LocalDate.of(2024, 12, 30)))
                        .evaluate(query(EntryPath.STRATEGY_RESULT));

        assertThat(shorter.filter().key()).isEqualTo(longer.filter().key());
        assertThat(filters.size()).isEqualTo(1);
    }

    @Test
    @DisplayName("과거 기준월은 그 달의 마지막 거래일까지만 세고, 그래서 현재 기준월과 필터 키가 다르다")
    void 과거_기준월은_그_달까지만_센다() {
        metadata.listed(SEMICONDUCTOR, LocalDate.of(2010, 1, 4));
        searches.holding(
                FakeRegimeSearches.view(
                        SNAPSHOT_KEY,
                        YearMonth.of(2015, 6),
                        "NEAREST_ONLY",
                        YearMonth.of(2015, 4)));

        AvailabilityResult past =
                service(calendar(), run(LocalDate.of(2010, 1, 4), LocalDate.of(2025, 12, 30)))
                        .evaluate(query(EntryPath.STRATEGY_RESULT));

        AvailabilityResult.CaseAvailability found = past.cases().get(0);
        // d0 = 2015-05-01 부터 2015-06-30 까지의 거래일만 센다. 63에 못 미치지만 21은 넘는다.
        assertThat(found.postWindow().completedTradingDays()).isEqualTo(43);
        assertThat(found.postWindow().complete63()).isFalse();
        assertThat(found.postWindow().complete21()).isTrue();
        assertThat(found.availability().state()).isEqualTo(AvailabilityState.NORMAL);

        searches.holding(
                FakeRegimeSearches.view(
                        SNAPSHOT_KEY,
                        YearMonth.of(2026, 7),
                        "NEAREST_ONLY",
                        YearMonth.of(2015, 4)));
        AvailabilityResult now =
                service(calendar(), run(LocalDate.of(2010, 1, 4), LocalDate.of(2025, 12, 30)))
                        .evaluate(query(EntryPath.STRATEGY_RESULT));

        assertThat(now.cases().get(0).postWindow().completedTradingDays()).isEqualTo(63);
        assertThat(now.filter().key()).isNotEqualTo(past.filter().key());
    }

    @Test
    @DisplayName("응답은 010 표시 후보만 담고 아래 순위에서 채우지 않는다")
    void 표시_후보_밖에서_채우지_않는다() {
        metadata.listed(SEMICONDUCTOR, LocalDate.of(2010, 1, 4));
        searches.holding(
                FakeRegimeSearches.view(
                        SNAPSHOT_KEY,
                        YearMonth.of(2026, 7),
                        "NEAREST_ONLY",
                        YearMonth.of(2018, 8),
                        YearMonth.of(2026, 6)));

        AvailabilityResult result =
                service(calendar(), run(LocalDate.of(2010, 1, 4), LocalDate.of(2026, 7, 15)))
                        .evaluate(query(EntryPath.STRATEGY_RESULT));

        assertThat(result.cases()).hasSize(2);
        assertThat(result.cases().stream().map(AvailabilityResult.CaseAvailability::rank))
                .containsExactly(1, 2);
        assertThat(result.filter().excludedCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("시장 흐름 경로는 필터를 걸지 않고 판정만 붙인다")
    void 시장_흐름_경로는_필터가_없다() {
        metadata.listed(SEMICONDUCTOR, LocalDate.of(2010, 1, 4));
        searches.holding(
                FakeRegimeSearches.view(
                        SNAPSHOT_KEY,
                        YearMonth.of(2026, 7),
                        "NEAREST_ONLY",
                        YearMonth.of(2026, 6)));

        AvailabilityResult result =
                service(calendar(), run(LocalDate.of(2010, 1, 4), LocalDate.of(2026, 7, 15)))
                        .evaluate(query(EntryPath.MARKET_FLOW));

        assertThat(result.filter().applied()).isFalse();
        assertThat(result.filter().key()).isNull();
        assertThat(result.filter().excludedCount()).isNull();
        assertThat(result.cases().get(0).shownInFiltered()).isTrue();
        assertThat(result.cases().get(0).availability().state())
                .isEqualTo(AvailabilityState.POST_INCOMPLETE);
        assertThat(filters.size()).isZero();
    }

    private AvailabilityService service(FakeTradingCalendar calendar, BundleRunView... runs) {
        BacktestRunReadPort bundles = new FakeBacktestRuns(List.of(runs));
        return new AvailabilityService(
                searches,
                bundles,
                calendar,
                metadata,
                filters,
                new FilterKeyCalculator(),
                new BundleConsistencyChecker());
    }

    private FakeTradingCalendar calendar() {
        return new FakeTradingCalendar(DATA_AS_OF);
    }

    private static AvailabilityQuery query(EntryPath entryPath) {
        return new AvailabilityQuery(
                "9f2c1a7b4e0d83526af1c9d0b7e34512",
                null,
                SNAPSHOT_KEY,
                null,
                null,
                null,
                entryPath);
    }

    private static BundleRunView run(LocalDate start, LocalDate end) {
        return run(
                "9f2c1a7b4e0d83526af1c9d0b7e34512",
                LinkRole.BASE,
                1L,
                List.of(entry("SEMICONDUCTOR", SEMICONDUCTOR)),
                start,
                end);
    }

    private static BundleRunView run(
            String token,
            LinkRole role,
            long runId,
            List<BundleRunView.UniverseEntry> universe,
            LocalDate start,
            LocalDate end) {
        return run(token, role, runId, universe, start, end, end);
    }

    /** 결과가 요청 종료일보다 앞에서 끊긴 실행(취소된 실행)을 만든다. */
    private static BundleRunView run(
            String token,
            LinkRole role,
            long runId,
            List<BundleRunView.UniverseEntry> universe,
            LocalDate start,
            LocalDate end,
            LocalDate resultEnd) {
        return new BundleRunView(
                token,
                role,
                runId,
                runId,
                "FIXED_WEIGHT",
                "내가 정한 비중",
                "MAP-1",
                new java.math.BigDecimal("10000000.00"),
                "KR-ETF-2026-09",
                "KRX-2026-09-09",
                universe,
                start,
                end,
                start,
                resultEnd,
                63,
                true);
    }

    private static BundleRunView.UniverseEntry entry(String sectorId, String instrumentId) {
        return new BundleRunView.UniverseEntry(sectorId, instrumentId);
    }
}
