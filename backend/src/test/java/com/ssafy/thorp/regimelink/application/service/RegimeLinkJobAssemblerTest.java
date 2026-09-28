package com.ssafy.thorp.regimelink.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.ssafy.thorp.regimelink.application.port.BundleRunView;
import com.ssafy.thorp.regimelink.application.port.JobSizeLimits;
import com.ssafy.thorp.regimelink.application.port.RunSlice;
import com.ssafy.thorp.regimelink.application.port.message.RegimeLinkJob;
import com.ssafy.thorp.regimelink.application.port.message.RegimeLinkScenario;
import com.ssafy.thorp.regimelink.domain.model.Availability;
import com.ssafy.thorp.regimelink.domain.model.PostWindow;
import com.ssafy.thorp.regimelink.domain.model.RegimeLink;
import com.ssafy.thorp.regimelink.domain.vo.CaseRef;
import com.ssafy.thorp.regimelink.domain.vo.LinkRole;
import com.ssafy.thorp.regimelink.domain.vo.LinkStatus;
import com.ssafy.thorp.regimelink.domain.vo.Scenario;
import com.ssafy.thorp.regimelink.support.FakeBacktestRuns;
import com.ssafy.thorp.regimelink.support.FakeTradingCalendar;
import com.ssafy.thorp.regimelink.support.InMemoryLinkRepository;
import com.ssafy.thorp.regimelink.support.RecordingJobPublisher;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 작업 조립과 발행 관문 (T035).
 *
 * <p>여기서 보는 것은 <b>무엇을 담아 보내는가</b>와 <b>언제 보내지 않는가</b>다. 수익률·낙폭은 워커 몫이라 이 시험에 없다.
 */
@DisplayName("사례 연결 작업 조립")
class RegimeLinkJobAssemblerTest {

    private static final String SNAPSHOT_KEY = "FIELD:LABOR:2026-07-01:DATA-1:REG-1:MODEL-1";
    private static final YearMonth END_MONTH = YearMonth.of(2024, 3);
    private static final LocalDate D0 = LocalDate.of(2024, 4, 1);
    private static final LocalDate BASELINE = LocalDate.of(2024, 3, 29);
    private static final long RUN_ID = 77L;

    private final FakeTradingCalendar calendar = new FakeTradingCalendar(LocalDate.of(2026, 9, 21));
    private final InMemoryLinkRepository links = new InMemoryLinkRepository();
    private final RecordingJobPublisher publisher = new RecordingJobPublisher();

    @Test
    @DisplayName("조각 범위는 기준일부터 확보한 마지막 거래일까지다")
    void 조각_범위() {
        RegimeLink link = links.create(queued(63));
        assembler(runs(true), slice(64, 3)).publish(link.id());

        RegimeLinkJob job = publisher.only();
        assertThat(job.window().baselineDate()).isEqualTo(BASELINE);
        assertThat(job.window().d0()).isEqualTo(D0);
        assertThat(job.window().completedTradingDays()).isEqualTo(63);
        assertThat(job.window().lastAvailable())
                .isEqualTo(calendar.nthTradingDayFrom(D0, 63).orElseThrow());
        assertThat(job.job().attempt()).isEqualTo(1);
        assertThat(job.job().scenario()).isEqualTo(RegimeLinkScenario.CONTINUOUS);
    }

    @Test
    @DisplayName("구간이 짧아진 부분 사례는 d63 을 싣지 않는다")
    void 부분_구간() {
        RegimeLink link = links.create(partial(30));
        assembler(runs(true), slice(31, 3)).publish(link.id());

        RegimeLinkJob job = publisher.only();
        assertThat(job.window().completedTradingDays()).isEqualTo(30);
        // 달력상의 d63 은 폐지 뒤라 계산할 수 없다. 그 날짜를 실으면 워커가 구간 밖을 주 구간의 끝으로 본다.
        assertThat(job.window().d63()).isNull();
        assertThat(job.window().lastAvailable())
                .isEqualTo(calendar.nthTradingDayFrom(D0, 30).orElseThrow());
    }

    @Test
    @DisplayName("구간 안 상장폐지일을 종목마다 실어 보낸다")
    void 상장폐지_전달() {
        calendar.delisted("244580.XKRX", LocalDate.of(2024, 5, 20));
        RegimeLink link = links.create(queued(63));
        assembler(runs(true), slice(64, 3)).publish(link.id());

        assertThat(publisher.only().universe().items())
                .anySatisfy(
                        item -> {
                            assertThat(item.instrumentId()).isEqualTo("244580.XKRX");
                            assertThat(item.delistedOn()).isEqualTo(LocalDate.of(2024, 5, 20));
                        });
    }

    @Test
    @DisplayName("같은 입력이면 같은 inputDigest 다")
    void 해시_결정성() {
        RegimeLink first = links.create(queued(63));
        assembler(runs(true), slice(64, 3)).publish(first.id());
        String before = publisher.only().inputDigest();

        RecordingJobPublisher second = new RecordingJobPublisher();
        InMemoryLinkRepository other = new InMemoryLinkRepository();
        RegimeLink again = other.create(queued(63));
        new RegimeLinkJobAssembler(
                        runs(true).slice(RUN_ID, slice(64, 3)),
                        calendar,
                        other,
                        second,
                        new InputDigestCalculator(),
                        new JobSizeLimits(65, 8),
                        clock())
                .publish(again.id());

        assertThat(second.only().inputDigest()).isEqualTo(before);
        assertThat(before).hasSize(64);
    }

    @Test
    @DisplayName("크기 상한을 넘으면 발행하지 않고 INPUT_TOO_LARGE 로 끝낸다")
    void 상한_초과() {
        RegimeLink link = links.create(queued(63));
        assembler(runs(true), slice(200, 3)).publish(link.id());

        assertThat(publisher.published()).isEmpty();
        RegimeLink after = links.findById(link.id()).orElseThrow();
        assertThat(after.status()).isEqualTo(LinkStatus.FAILED);
        assertThat(after.failure().code()).isEqualTo("INPUT_TOO_LARGE");
        // 발행 전 판정이라 시도 횟수와 무관하게 결정적이다 — 다시 시도를 두지 않는다.
        assertThat(after.failure().retryable(1)).isFalse();
    }

    @Test
    @DisplayName("상한은 portfolioSeries 날짜 행 수와 스냅샷 한 건의 종목 수로 잰다 (T104)")
    void 상한_기준() {
        // 날짜 행이 65 를 넘지 않으면 종목 3개(상한 8 안)는 통과한다 — dailyPositions 행 수로 재지 않는다.
        RegimeLink within = links.create(queued(63));
        assembler(runs(true), slice(65, 3)).publish(within.id());
        assertThat(publisher.published()).hasSize(1);

        // 종목 수가 대응표 섹터 수(8)를 넘으면 날짜 행이 적어도 막는다.
        RecordingJobPublisher second = new RecordingJobPublisher();
        RegimeLink wide = links.create(queued(63));
        new RegimeLinkJobAssembler(
                        runs(true).slice(RUN_ID, slice(64, 9)),
                        calendar,
                        links,
                        second,
                        new InputDigestCalculator(),
                        new JobSizeLimits(65, 8),
                        clock())
                .publish(wide.id());
        assertThat(second.published()).isEmpty();
        assertThat(links.findById(wide.id()).orElseThrow().failure().code())
                .isEqualTo("INPUT_TOO_LARGE");
    }

    @Test
    @DisplayName("입력 실행이 끝나지 않았으면 발행하지 않고 상태도 바꾸지 않는다")
    void 미완료_실행() {
        RegimeLink link = links.create(queued(63));
        assembler(runs(false), slice(64, 3)).publish(link.id());

        assertThat(publisher.published()).isEmpty();
        assertThat(links.findById(link.id()).orElseThrow().status()).isEqualTo(LinkStatus.QUEUED);
    }

    @Test
    @DisplayName("체결 기록이 없는 실행은 발행하지 않는다")
    void 체결_기록_없음() {
        RegimeLink link = links.create(queued(63));
        RunSlice slice = slice(64, 3);
        FakeBacktestRuns runs =
                new FakeBacktestRuns(List.of(run(true)))
                        .slice(
                                RUN_ID,
                                new RunSlice(
                                        slice.run(),
                                        slice.universe(),
                                        slice.marketData(),
                                        slice.inputs(),
                                        false,
                                        "ANALYTICS-1"));
        assembler(runs, null).publish(link.id());

        assertThat(publisher.published()).isEmpty();
        assertThat(links.findById(link.id()).orElseThrow().failure().code())
                .isEqualTo("INPUT_INCOMPLETE");
    }

    // --- 도우미 -------------------------------------------------------------------

    private RegimeLinkJobAssembler assembler(FakeBacktestRuns runs, RunSlice slice) {
        if (slice != null) {
            runs.slice(RUN_ID, slice);
        }
        return new RegimeLinkJobAssembler(
                runs,
                calendar,
                links,
                publisher,
                new InputDigestCalculator(),
                new JobSizeLimits(65, 8),
                clock());
    }

    private static Clock clock() {
        return Clock.fixed(Instant.parse("2026-09-21T00:00:00Z"), ZoneOffset.UTC);
    }

    private FakeBacktestRuns runs(boolean completed) {
        return new FakeBacktestRuns(List.of(run(completed)));
    }

    private static BundleRunView run(boolean completed) {
        return new BundleRunView(
                "9f2c1a7b4e0d83526af1c9d0b7e34512",
                LinkRole.BASE,
                101L,
                RUN_ID,
                "FIXED_WEIGHT",
                "내가 정한 비중",
                "MAP-1",
                new BigDecimal("10000000.00"),
                "KR-ETF-2026-09",
                "KRX-2026-09-09",
                List.of(new BundleRunView.UniverseEntry("BIO", "244580.XKRX")),
                LocalDate.of(2010, 1, 4),
                LocalDate.of(2026, 6, 30),
                LocalDate.of(2010, 4, 2),
                LocalDate.of(2026, 6, 30),
                63,
                completed);
    }

    private static RegimeLink queued(int completedTradingDays) {
        return RegimeLink.pending(
                RUN_ID,
                new CaseRef(SNAPSHOT_KEY, END_MONTH),
                Scenario.CONTINUOUS,
                window(completedTradingDays),
                Availability.normal(completedTradingDays),
                LinkStatus.QUEUED);
    }

    private static RegimeLink partial(int completedTradingDays) {
        return RegimeLink.pending(
                RUN_ID,
                new CaseRef(SNAPSHOT_KEY, END_MONTH),
                Scenario.CONTINUOUS,
                window(63),
                Availability.partial(LocalDate.of(2024, 5, 20), completedTradingDays),
                LinkStatus.QUEUED);
    }

    /** 달력 기준 구간. 부분 사례에서도 이 값은 63 이고 줄어드는 것은 판정 쪽이다. */
    private static PostWindow window(int completedTradingDays) {
        return new PostWindow(
                D0,
                LocalDate.of(2024, 4, 29),
                LocalDate.of(2024, 6, 27),
                LocalDate.of(2024, 6, 27),
                completedTradingDays,
                true,
                true);
    }

    private static RunSlice slice(int rows, int instruments) {
        List<RegimeLinkJob.PortfolioPoint> series = new ArrayList<>();
        for (int day = 0; day < rows; day++) {
            series.add(
                    new RegimeLinkJob.PortfolioPoint(
                            BASELINE.plusDays(day),
                            new BigDecimal("10000000.00"),
                            new BigDecimal("120000.00")));
        }
        List<RegimeLinkJob.Position> positions = new ArrayList<>();
        for (int index = 0; index < instruments; index++) {
            positions.add(new RegimeLinkJob.Position("0911" + index + "0.XKRX", 100L + index));
        }
        return new RunSlice(
                new RegimeLinkJob.RunSpec(
                        "BT-20260101-000001",
                        LocalDate.of(2010, 4, 2),
                        new BigDecimal("10000000.00"),
                        null),
                new RegimeLinkJob.UniverseSpec(
                        "SECTOR-ETF-2026-09",
                        List.of(new RegimeLinkJob.UniverseItem("BIO", "244580.XKRX", null))),
                new RegimeLinkJob.MarketDataSpec("KRX-2026-09-09", "KRX-2026-09-09", "KOSPI.XKRX"),
                new RegimeLinkJob.InputsSpec(
                        series,
                        positions,
                        List.of(new RegimeLinkJob.DailyPositions(D0, positions)),
                        List.of(),
                        List.of()),
                true,
                "ANALYTICS-1");
    }
}
