package com.ssafy.thorp.regimelink.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ssafy.thorp.common.exception.BusinessException;
import com.ssafy.thorp.common.exception.ErrorCode;
import com.ssafy.thorp.regimelink.application.command.CreateLinkSearchCommand;
import com.ssafy.thorp.regimelink.application.port.BundleRunView;
import com.ssafy.thorp.regimelink.application.result.LinkSearchResult;
import com.ssafy.thorp.regimelink.domain.vo.EntryPath;
import com.ssafy.thorp.regimelink.domain.vo.LinkRole;
import com.ssafy.thorp.regimelink.support.FakeBacktestRuns;
import com.ssafy.thorp.regimelink.support.FakeMarketMetadata;
import com.ssafy.thorp.regimelink.support.FakeRegimeSearches;
import com.ssafy.thorp.regimelink.support.FakeTradingCalendar;
import com.ssafy.thorp.regimelink.support.InMemoryFilterRepository;
import com.ssafy.thorp.regimelink.support.InMemorySearchRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 비교 묶음 변경 알림 (T076, contracts §1b · FR-021).
 *
 * <p>사례를 하나도 열지 않아도 후보가 달라진 사실은 <b>검색 기록을 만들 때</b> 알린다. 이 시험은 그 알림이 무엇으로 갈리는지를 본다 — 비교 묶음이 달라져 필터가
 * 달라지면 알리고, 같으면 알리지 않는다.
 *
 * <p>검색 생성 경로에는 발행기가 주입되지 않는다 — "검색 생성 시 계산 메시지 0"은 단언이 아니라 {@link RegimeLinkSearchService} 의 의존
 * 관계로 강제된다. 큐에 드는 것은 사례를 열 때뿐이다.
 */
@DisplayName("비교 묶음 변경 알림")
class BundleChangeTest {

    private static final String SNAPSHOT_KEY = "FIELD:LABOR:2026-07-01:DATA-1:REG-1:MODEL-1";
    private static final String SEMICONDUCTOR = "091160.XKRX";
    private static final String BIO = "244580.XKRX";
    private static final String TOKEN = "9f2c1a7b4e0d83526af1c9d0b7e34512";
    private static final LocalDate DATA_AS_OF = LocalDate.of(2026, 7, 15);
    private static final YearMonth OLD_CASE = YearMonth.of(2018, 8);
    private static final YearMonth NEW_CASE = YearMonth.of(2019, 5);

    private final FakeMarketMetadata metadata = new FakeMarketMetadata();
    private final FakeRegimeSearches searches = new FakeRegimeSearches();
    private final InMemoryFilterRepository filters = new InMemoryFilterRepository();
    private final FakeTradingCalendar calendar = new FakeTradingCalendar(DATA_AS_OF);
    private final InMemorySearchRepository searchRecords = new InMemorySearchRepository();

    @BeforeEach
    void seed() {
        // 반도체는 2010년 상장이라 두 사례를 다 볼 수 있고, 바이오는 2019년 상장이라 2018년 사례를
        // 워밍업까지 채우지 못한다 — 묶음을 바꾸면 보이는 사례가 줄어든다.
        metadata.listed(SEMICONDUCTOR, LocalDate.of(2010, 1, 4))
                .listed(BIO, LocalDate.of(2019, 1, 2));
        searches.holding(
                FakeRegimeSearches.view(
                        SNAPSHOT_KEY, YearMonth.of(2026, 7), "NEAREST_ONLY", OLD_CASE, NEW_CASE));
    }

    @Test
    @DisplayName("묶음이 달라져 후보가 줄면 사례를 열지 않아도 빠진 사례와 제외 수를 알린다")
    void 묶음이_바뀌면_알린다() {
        long owner = 400L;
        LinkSearchResult first = create(owner, "bc-1", semiconductor(), null);
        assertThat(first.bundleChanged()).isNull();

        LinkSearchResult second = create(owner, "bc-2", bio(), first);

        assertThat(second.created()).isTrue();
        assertThat(second.bundleChanged()).isNotNull();
        assertThat(second.bundleChanged().removed()).containsExactly(OLD_CASE);
        assertThat(second.bundleChanged().added()).isEmpty();
        // 변경 뒤 목록이 줄어도 무엇이 빠졌는지는 남는다(FR-021).
        assertThat(second.bundleChanged().excludedCount()).isEqualTo(1);
        assertThat(searchRecords.size()).isEqualTo(2);
    }

    @Test
    @DisplayName("같은 묶음으로 다시 찾으면 알림이 없다")
    void 묶음이_같으면_알리지_않는다() {
        long owner = 401L;
        LinkSearchResult first = create(owner, "bc-3", bio(), null);

        LinkSearchResult second = create(owner, "bc-4", bio(), first);

        assertThat(second.bundleChanged()).isNull();
        assertThat(searchRecords.size()).isEqualTo(2);
    }

    @Test
    @DisplayName("남의 직전 기록을 견주려 하면 404 이고 기록을 만들지 않는다")
    void 남의_직전_기록은_거절한다() {
        LinkSearchResult mine = create(400L, "bc-5", semiconductor(), null);

        assertThatThrownBy(() -> create(999L, "bc-6", bio(), mine))
                .isInstanceOf(BusinessException.class)
                .extracting(found -> ((BusinessException) found).getErrorCode())
                .isEqualTo(ErrorCode.RESOURCE_NOT_FOUND);

        assertThat(searchRecords.size()).isEqualTo(1);
    }

    private LinkSearchResult create(
            long ownerUserId,
            String clientRequestId,
            BundleRunView run,
            LinkSearchResult previous) {
        return service(run)
                .create(
                        new CreateLinkSearchCommand(
                                ownerUserId,
                                clientRequestId,
                                SNAPSHOT_KEY,
                                TOKEN,
                                null,
                                EntryPath.STRATEGY_RESULT,
                                true,
                                previous == null ? null : previous.searchId()));
    }

    private RegimeLinkSearchService service(BundleRunView run) {
        FakeBacktestRuns runs = new FakeBacktestRuns(List.of(run));
        AvailabilityService availability =
                new AvailabilityService(
                        searches,
                        runs,
                        calendar,
                        metadata,
                        filters,
                        new FilterKeyCalculator(),
                        new BundleConsistencyChecker());
        return new RegimeLinkSearchService(availability, searches, searchRecords, filters);
    }

    private static BundleRunView semiconductor() {
        return run(1L, "SEMICONDUCTOR", SEMICONDUCTOR, LocalDate.of(2010, 1, 4));
    }

    private static BundleRunView bio() {
        return run(2L, "BIO", BIO, LocalDate.of(2019, 1, 2));
    }

    private static BundleRunView run(
            long runId, String sectorId, String instrumentId, LocalDate start) {
        return new BundleRunView(
                TOKEN,
                LinkRole.BASE,
                runId,
                runId,
                "FIXED_WEIGHT",
                "내가 정한 비중",
                "MAP-1",
                new BigDecimal("10000000.00"),
                "KR-ETF-2026-09",
                "KRX-2026-09-09",
                List.of(new BundleRunView.UniverseEntry(sectorId, instrumentId)),
                start,
                LocalDate.of(2025, 12, 30),
                start,
                LocalDate.of(2025, 12, 30),
                63,
                true);
    }
}
