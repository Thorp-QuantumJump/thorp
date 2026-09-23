package com.ssafy.thorp.regimelink.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.ssafy.thorp.regimelink.application.command.AvailabilityQuery;
import com.ssafy.thorp.regimelink.application.command.OpenRegimeLinkCommand;
import com.ssafy.thorp.regimelink.application.port.BundleRunView;
import com.ssafy.thorp.regimelink.application.port.RunSlice;
import com.ssafy.thorp.regimelink.application.result.AvailabilityResult;
import com.ssafy.thorp.regimelink.application.result.OpenLinkResult;
import com.ssafy.thorp.regimelink.domain.model.PostWindow;
import com.ssafy.thorp.regimelink.domain.model.RegimeLinkSearch;
import com.ssafy.thorp.regimelink.domain.model.RegimeLinkUse;
import com.ssafy.thorp.regimelink.domain.vo.CaseRef;
import com.ssafy.thorp.regimelink.domain.vo.EntryPath;
import com.ssafy.thorp.regimelink.domain.vo.LinkRole;
import com.ssafy.thorp.regimelink.domain.vo.LinkStatus;
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
 * 현금 시작 시나리오의 004 접수 (T060, contracts §2).
 *
 * <p><b>004 접수는 가짜로 끼우고 호출 횟수와 본문만 본다.</b> 실제 접수를 부르려면 전략 규칙과 비용 등록표까지 맞춘 픽스처가 필요한데, 여기서 확인하려는 것은
 * 011 이 무엇을 접수하느냐(어느 실행의 조건으로, 어느 기간을, 누구 이름으로)이지 004 가 그것을 어떻게 계산하느냐가 아니다.
 *
 * <p>달력은 평일이 거래일인 가짜다 — 워밍업을 거슬러 세는 규칙을 보려는 것이지 특정 해의 휴장일을 보려는 것이 아니다.
 */
@DisplayName("현금 시작 시나리오")
class FreshCashScenarioTest {

    private static final String SNAPSHOT_KEY = "FIELD:LABOR:2026-07-01:DATA-1:REG-1:MODEL-1";
    private static final String SEMICONDUCTOR = "091160.XKRX";
    private static final String TOKEN = "9f2c1a7b4e0d83526af1c9d0b7e34512";
    private static final LocalDate DATA_AS_OF = LocalDate.of(2026, 7, 15);
    private static final YearMonth CASE_MONTH = YearMonth.of(2018, 8);
    private static final CaseRef CASE = new CaseRef(SNAPSHOT_KEY, CASE_MONTH);
    private static final long ORIGIN_RUN = 1L;
    private static final long ORIGIN_REQUEST = 11L;
    private static final long DERIVED_RUN = 9L;
    private static final int WARMUP = 63;

    private final FakeMarketMetadata metadata = new FakeMarketMetadata();
    private final FakeRegimeSearches searches = new FakeRegimeSearches();
    private final InMemoryFilterRepository filters = new InMemoryFilterRepository();
    private final FakeTradingCalendar calendar = new FakeTradingCalendar(DATA_AS_OF);
    private final InMemorySearchRepository searchRecords = new InMemorySearchRepository();
    private final InMemoryLinkRepository links = new InMemoryLinkRepository();
    private final InMemoryUseRepository uses = new InMemoryUseRepository();
    private final List<Object> events = new ArrayList<>();

    private FakeBacktestRuns runs;

    @BeforeEach
    void seed() {
        metadata.listed(SEMICONDUCTOR, LocalDate.of(2010, 1, 4));
        searches.holding(
                FakeRegimeSearches.view(
                        SNAPSHOT_KEY, YearMonth.of(2026, 7), "NEAREST_ONLY", CASE_MONTH));
        runs = new FakeBacktestRuns(List.of(run()));
        // 체결 기록이 있는 실행이다 — 없으면 시나리오와 무관하게 재계산 확인부터 받는다(T092).
        runs.slice(ORIGIN_RUN, new RunSlice(null, null, null, null, true, "v1"));
    }

    @Test
    @DisplayName("현금 시작은 워밍업만큼 앞선 날부터 d63 까지를 요청자 이름으로 한 번 접수한다")
    void 접수_본문이_계약과_같다() {
        RecordingBacktestAcceptance acceptance = new RecordingBacktestAcceptance(DERIVED_RUN, true);
        RegimeLinkOpenService service = service(acceptance);
        long owner = 100L;
        RegimeLinkSearch search = saveSearch(owner, "fresh-1");
        PostWindow window = window();

        OpenLinkResult result =
                service.open(
                        new OpenRegimeLinkCommand(
                                owner, search.searchId(), CASE, Scenario.FRESH_CASH, false));

        assertThat(result.outcome()).isEqualTo(OpenLinkResult.Outcome.ACCEPTED);
        assertThat(acceptance.calls()).hasSize(1);
        RecordingBacktestAcceptance.Call call = acceptance.only();
        assertThat(call.kind()).isEqualTo(RecordingBacktestAcceptance.Kind.FRESH_CASH);
        assertThat(call.originRunId()).isEqualTo(ORIGIN_RUN);
        assertThat(call.requesterId()).isEqualTo(owner);
        assertThat(call.d0()).isEqualTo(window.d0());
        assertThat(call.end()).isEqualTo(window.d63());
        // 시작일은 d0 에서 워밍업 거래일만큼 거슬러 올라간 날이다 — 그날부터 d0 까지가 64 거래일.
        assertThat(call.start()).isBefore(window.d0());
        assertThat(calendar.tradingDaysBetween(call.start(), window.d0())).isEqualTo(WARMUP + 1);
    }

    @Test
    @DisplayName("접수한 실행에 이미 결과가 있으면 기다리지 않고 바로 QUEUED 다")
    void 재사용된_실행은_바로_큐에_든다() {
        RecordingBacktestAcceptance acceptance = new RecordingBacktestAcceptance(DERIVED_RUN, true);
        RegimeLinkOpenService service = service(acceptance);
        long owner = 101L;
        RegimeLinkSearch search = saveSearch(owner, "fresh-2");

        OpenLinkResult result =
                service.open(
                        new OpenRegimeLinkCommand(
                                owner, search.searchId(), CASE, Scenario.FRESH_CASH, false));

        assertThat(result.links()).hasSize(1);
        assertThat(result.links().get(0).status()).isEqualTo(LinkStatus.QUEUED);
        assertThat(result.links().get(0).scenario()).isEqualTo(Scenario.FRESH_CASH);
        // 링크는 원천 실행이 아니라 접수로 얻은 실행을 기다린다.
        assertThat(links.findUnit(DERIVED_RUN, CASE, Scenario.FRESH_CASH)).isPresent();
        assertThat(links.size()).isEqualTo(1);
        assertThat(events).hasSize(1);
    }

    @Test
    @DisplayName("접수한 실행이 아직 끝나지 않았으면 PENDING_RUN 으로 기다리고 작업을 만들지 않는다")
    void 끝나지_않은_실행은_기다린다() {
        RecordingBacktestAcceptance acceptance =
                new RecordingBacktestAcceptance(DERIVED_RUN, false);
        RegimeLinkOpenService service = service(acceptance);
        long owner = 102L;
        RegimeLinkSearch search = saveSearch(owner, "fresh-3");

        OpenLinkResult result =
                service.open(
                        new OpenRegimeLinkCommand(
                                owner, search.searchId(), CASE, Scenario.FRESH_CASH, false));

        assertThat(result.links().get(0).status()).isEqualTo(LinkStatus.PENDING_RUN);
        assertThat(events).isEmpty();
    }

    @Test
    @DisplayName("두 사용자가 같은 사례를 켜도 계산은 하나이고 분석 요청과 이용 기록은 각자 하나다")
    void 두_사용자가_같은_계산을_나눠_쓴다() {
        RecordingBacktestAcceptance acceptance = new RecordingBacktestAcceptance(DERIVED_RUN, true);
        RegimeLinkOpenService service = service(acceptance);
        long first = 201L;
        long second = 202L;
        RegimeLinkSearch firstSearch = saveSearch(first, "fresh-4");
        RegimeLinkSearch secondSearch = saveSearch(second, "fresh-5");

        service.open(
                new OpenRegimeLinkCommand(
                        first, firstSearch.searchId(), CASE, Scenario.FRESH_CASH, false));
        service.open(
                new OpenRegimeLinkCommand(
                        second, secondSearch.searchId(), CASE, Scenario.FRESH_CASH, false));

        assertThat(links.size()).isEqualTo(1);
        assertThat(uses.size()).isEqualTo(2);
        // 004 는 실행을 재사용해도 호출자마다 새 요청을 만든다 — 접수도 요청 식별자도 각자 하나다.
        assertThat(acceptance.calls()).hasSize(2);
        assertThat(acceptance.calls().stream().map(RecordingBacktestAcceptance.Call::requesterId))
                .containsExactly(first, second);
        assertThat(uses.all().stream().map(RegimeLinkUse::createdRequestId))
                .doesNotHaveDuplicates();
    }

    private RegimeLinkOpenService service(RecordingBacktestAcceptance acceptance) {
        AvailabilityService availability = availability();
        ApplicationEventPublisher publisher = events::add;
        return new RegimeLinkOpenService(
                availability, searchRecords, links, uses, runs, acceptance, calendar, publisher);
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

    /** 이 사례의 사후 구간. 접수 본문의 기대값을 판정과 같은 코드에서 얻는다. */
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

    private RegimeLinkSearch saveSearch(long ownerUserId, String clientRequestId) {
        return searchRecords.save(
                new RegimeLinkSearch(
                        null,
                        null,
                        ownerUserId,
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
                LocalDate.of(2010, 1, 4),
                LocalDate.of(2025, 12, 30),
                LocalDate.of(2010, 1, 4),
                LocalDate.of(2025, 12, 30),
                WARMUP,
                true);
    }
}
