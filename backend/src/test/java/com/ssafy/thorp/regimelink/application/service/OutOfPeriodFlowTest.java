package com.ssafy.thorp.regimelink.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.ssafy.thorp.regimelink.application.command.AvailabilityQuery;
import com.ssafy.thorp.regimelink.application.command.OpenRegimeLinkCommand;
import com.ssafy.thorp.regimelink.application.port.BundleRunView;
import com.ssafy.thorp.regimelink.application.result.AvailabilityResult;
import com.ssafy.thorp.regimelink.application.result.OpenLinkResult;
import com.ssafy.thorp.regimelink.domain.model.PostWindow;
import com.ssafy.thorp.regimelink.domain.model.RegimeLinkSearch;
import com.ssafy.thorp.regimelink.domain.vo.AvailabilityState;
import com.ssafy.thorp.regimelink.domain.vo.CaseRef;
import com.ssafy.thorp.regimelink.domain.vo.EntryPath;
import com.ssafy.thorp.regimelink.domain.vo.LinkRole;
import com.ssafy.thorp.regimelink.domain.vo.Scenario;
import com.ssafy.thorp.regimelink.support.FakeBacktestRuns;
import com.ssafy.thorp.regimelink.support.FakeMarketMetadata;
import com.ssafy.thorp.regimelink.support.FakeRegimeSearches;
import com.ssafy.thorp.regimelink.support.FakeTradingCalendar;
import com.ssafy.thorp.regimelink.support.InMemoryFilterRepository;
import com.ssafy.thorp.regimelink.support.InMemoryLinkRepository;
import com.ssafy.thorp.regimelink.support.InMemorySearchRepository;
import com.ssafy.thorp.regimelink.support.InMemoryUseRepository;
import com.ssafy.thorp.regimelink.support.RecordingBacktestAcceptance;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

/**
 * 분석 기간 밖 사례의 확인 흐름 (T070, contracts §2).
 *
 * <p>확인 전에는 <b>행도 메시지도 만들지 않는다</b>는 것이 이 시험의 중심이다. 확인하면 그때 004 에 전체 기간 실행을 <b>정확히 한 번</b> 접수한다 —
 * 접수는 가짜로 끼우고 호출 횟수와 기간만 본다.
 */
@DisplayName("분석 기간 밖 확인 흐름")
class OutOfPeriodFlowTest {

    private static final String SNAPSHOT_KEY = "FIELD:LABOR:2026-07-01:DATA-1:REG-1:MODEL-1";
    private static final String SEMICONDUCTOR = "091160.XKRX";
    private static final String TOKEN = "9f2c1a7b4e0d83526af1c9d0b7e34512";
    private static final LocalDate DATA_AS_OF = LocalDate.of(2026, 7, 15);
    private static final YearMonth CASE_MONTH = YearMonth.of(2018, 8);
    private static final CaseRef CASE = new CaseRef(SNAPSHOT_KEY, CASE_MONTH);
    private static final long ORIGIN_RUN = 1L;
    private static final long ORIGIN_REQUEST = 11L;
    private static final long DERIVED_RUN = 9L;

    private final FakeMarketMetadata metadata = new FakeMarketMetadata();
    private final FakeRegimeSearches searches = new FakeRegimeSearches();
    private final InMemoryFilterRepository filters = new InMemoryFilterRepository();
    private final FakeTradingCalendar calendar = new FakeTradingCalendar(DATA_AS_OF);
    private final InMemorySearchRepository searchRecords = new InMemorySearchRepository();
    private final InMemoryLinkRepository links = new InMemoryLinkRepository();
    private final InMemoryUseRepository uses = new InMemoryUseRepository();
    private final RecordingBacktestAcceptance acceptance =
            new RecordingBacktestAcceptance(DERIVED_RUN, true);
    private final List<Object> events = new ArrayList<>();

    private FakeBacktestRuns runs;
    private long owner;
    private RegimeLinkSearch search;

    @BeforeEach
    void seed() {
        metadata.listed(SEMICONDUCTOR, LocalDate.of(2010, 1, 4));
        searches.holding(
                FakeRegimeSearches.view(
                        SNAPSHOT_KEY, YearMonth.of(2026, 7), "NEAREST_ONLY", CASE_MONTH));
        // 원천 자료는 2010년부터지만 기존 실행은 2022년부터다 — 2018년 사례를 덮지 않는다.
        runs = new FakeBacktestRuns(List.of(run()));
        owner = 300L;
        search = saveSearch("oop-1");
    }

    @Test
    @DisplayName("확인 전에는 링크도 이용 기록도 메시지도 만들지 않고 004 를 부르지도 않는다")
    void 확인_전에는_아무것도_만들지_않는다() {
        OpenLinkResult result =
                service()
                        .open(
                                new OpenRegimeLinkCommand(
                                        owner,
                                        search.searchId(),
                                        CASE,
                                        Scenario.CONTINUOUS,
                                        false));

        assertThat(result.outcome())
                .isEqualTo(OpenLinkResult.Outcome.OUT_OF_PERIOD_CONFIRM_REQUIRED);
        assertThat(result.affected()).hasSize(1);
        assertThat(result.affected().get(0).publicToken()).isEqualTo(TOKEN);
        assertThat(result.links()).isEmpty();
        assertThat(links.size()).isZero();
        assertThat(uses.size()).isZero();
        assertThat(events).isEmpty();
        assertThat(acceptance.calls()).isEmpty();
    }

    @Test
    @DisplayName("확인하면 사례를 덮는 기간으로 004 에 정확히 한 번 접수한다")
    void 확인하면_한_번_접수한다() {
        PostWindow window = window();

        OpenLinkResult result =
                service()
                        .open(
                                new OpenRegimeLinkCommand(
                                        owner, search.searchId(), CASE, Scenario.CONTINUOUS, true));

        assertThat(result.outcome()).isEqualTo(OpenLinkResult.Outcome.ACCEPTED);
        assertThat(acceptance.calls()).hasSize(1);
        RecordingBacktestAcceptance.Call call = acceptance.only();
        assertThat(call.kind()).isEqualTo(RecordingBacktestAcceptance.Kind.FULL_PERIOD);
        assertThat(call.originRunId()).isEqualTo(ORIGIN_RUN);
        assertThat(call.requesterId()).isEqualTo(owner);
        // 요청 기간은 사례 직전 거래일부터 사후 구간의 마지막 자료일까지다.
        assertThat(call.start())
                .isEqualTo(
                        calendar.lastTradingDayOnOrBefore(window.d0().minusDays(1)).orElseThrow());
        assertThat(call.end()).isEqualTo(window.lastAvailable());
        assertThat(links.size()).isEqualTo(1);
        assertThat(uses.size()).isEqualTo(1);
    }

    @Test
    @DisplayName("접수로 만든 링크에는 기간 밖 판정을 남기지 않는다")
    void 접수_뒤에는_기간_밖이_아니다() {
        service()
                .open(
                        new OpenRegimeLinkCommand(
                                owner, search.searchId(), CASE, Scenario.CONTINUOUS, true));

        assertThat(links.findUnit(DERIVED_RUN, CASE, Scenario.CONTINUOUS))
                .isPresent()
                .get()
                .satisfies(
                        link ->
                                assertThat(link.availability().state())
                                        .isNotEqualTo(AvailabilityState.OUT_OF_PERIOD));
    }

    private RegimeLinkOpenService service() {
        ApplicationEventPublisher publisher = events::add;
        return new RegimeLinkOpenService(
                availability(), searchRecords, links, uses, runs, acceptance, calendar, publisher);
    }

    private AvailabilityService availability() {
        return new AvailabilityService(
                searches,
                runs,
                calendar,
                metadata,
                filters,
                new FilterKeyCalculator(),
                new BundleConsistencyChecker());
    }

    private PostWindow window() {
        AvailabilityResult evaluated =
                availability()
                        .evaluate(
                                new AvailabilityQuery(
                                        TOKEN,
                                        null,
                                        SNAPSHOT_KEY,
                                        null,
                                        null,
                                        null,
                                        EntryPath.STRATEGY_RESULT));
        return evaluated.cases().get(0).postWindow();
    }

    private RegimeLinkSearch saveSearch(String clientRequestId) {
        return searchRecords.save(
                new RegimeLinkSearch(
                        null,
                        null,
                        owner,
                        EntryPath.STRATEGY_RESULT,
                        1L,
                        SNAPSHOT_KEY,
                        "FIELD",
                        "LABOR",
                        YearMonth.of(2026, 7),
                        ORIGIN_RUN,
                        null,
                        ORIGIN_REQUEST,
                        null,
                        1L,
                        List.of(),
                        0,
                        true,
                        null,
                        null,
                        RegimeLinkSearch.BASIS_HISTORICAL_REVISION,
                        clientRequestId,
                        null));
    }

    private static BundleRunView run() {
        return new BundleRunView(
                TOKEN,
                LinkRole.BASE,
                ORIGIN_REQUEST,
                ORIGIN_RUN,
                "FIXED_WEIGHT",
                "내가 정한 비중",
                "MAP-1",
                new BigDecimal("10000000.00"),
                "KR-ETF-2026-09",
                "KRX-2026-09-09",
                List.of(new BundleRunView.UniverseEntry("SEMICONDUCTOR", SEMICONDUCTOR)),
                LocalDate.of(2022, 1, 3),
                LocalDate.of(2025, 12, 30),
                LocalDate.of(2022, 1, 3),
                LocalDate.of(2025, 12, 30),
                63,
                true);
    }
}
