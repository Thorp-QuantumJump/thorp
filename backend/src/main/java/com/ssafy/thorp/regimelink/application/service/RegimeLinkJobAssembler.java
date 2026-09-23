package com.ssafy.thorp.regimelink.application.service;

import com.ssafy.thorp.regimelink.application.port.BacktestRunReadPort;
import com.ssafy.thorp.regimelink.application.port.BundleRunView;
import com.ssafy.thorp.regimelink.application.port.JobSizeLimits;
import com.ssafy.thorp.regimelink.application.port.RegimeLinkJobPublisher;
import com.ssafy.thorp.regimelink.application.port.RunProgressView;
import com.ssafy.thorp.regimelink.application.port.RunSlice;
import com.ssafy.thorp.regimelink.application.port.TradingCalendarPort;
import com.ssafy.thorp.regimelink.application.port.message.RegimeLinkJob;
import com.ssafy.thorp.regimelink.application.port.message.RegimeLinkScenario;
import com.ssafy.thorp.regimelink.application.repository.RegimeLinkRepository;
import com.ssafy.thorp.regimelink.domain.model.LinkFailure;
import com.ssafy.thorp.regimelink.domain.model.PostWindow;
import com.ssafy.thorp.regimelink.domain.model.RegimeLink;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 링크 하나의 작업 메시지를 조립해 발행한다 (T031).
 *
 * <p><b>시계열 산술을 하지 않는다.</b> 여기서 정하는 것은 범위(어디부터 어디까지)와 발행해도 되는지의 관문뿐이고, 수익률·낙폭·회전율은 워커가 낸다(원칙 1).
 *
 * <p>발행 전 관문은 계약 "검증 책임" 표 그대로다 — 입력 실행이 {@code COMPLETED}인가, 잘라낸 범위가 {@code baselineDate}~{@code
 * lastAvailable}를 덮는가, 체결 기록이 있는가, 크기 상한을 넘지 않는가. 하나라도 걸리면 <b>메시지를 만들지 않고</b> 링크를 끝낸다.
 */
@Service
public class RegimeLinkJobAssembler {

    private static final Logger log = LoggerFactory.getLogger(RegimeLinkJobAssembler.class);

    /** 크기 상한을 넘겨 발행하지 않은 링크의 실패 코드(계약 "크기 상한"). 워커 오류 코드와 섞지 않는다. */
    public static final String INPUT_TOO_LARGE = "INPUT_TOO_LARGE";

    /** 004 저장 결과에서 구간을 되살릴 수 없는 링크. 워커가 냈다면 같은 이름이었을 실패다. */
    public static final String INPUT_INCOMPLETE = "INPUT_INCOMPLETE";

    private final BacktestRunReadPort runs;
    private final TradingCalendarPort calendar;
    private final RegimeLinkRepository links;
    private final RegimeLinkJobPublisher publisher;
    private final InputDigestCalculator digests;
    private final JobSizeLimits limits;
    private final Clock clock;

    public RegimeLinkJobAssembler(
            BacktestRunReadPort runs,
            TradingCalendarPort calendar,
            RegimeLinkRepository links,
            RegimeLinkJobPublisher publisher,
            InputDigestCalculator digests,
            JobSizeLimits limits,
            Clock clock) {
        this.runs = runs;
        this.calendar = calendar;
        this.links = links;
        this.publisher = publisher;
        this.digests = digests;
        this.limits = limits;
        this.clock = clock;
    }

    /**
     * 링크 하나를 발행한다. 접수 트랜잭션이 커밋된 뒤에 불리므로 자기 트랜잭션을 연다.
     *
     * <p>이미 종결된 링크는 건드리지 않는다 — 감시와 이벤트가 같은 링크를 동시에 볼 수 있고, 끝난 계산에 작업을 더 보내면 결과가 두 번 저장된다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void publish(Long linkDbId) {
        RegimeLink link = links.findById(linkDbId).orElse(null);
        if (link == null || link.status().terminal()) {
            return;
        }
        BundleRunView run = runs.findRun(link.runId()).orElse(null);
        if (run == null || !run.completed()) {
            // 아직 끝나지 않은 실행이다. 감시(PendingRunSweeper)가 다시 본다.
            return;
        }
        LocalDate d0 = link.postWindow().d0();
        LocalDate baselineDate = calendar.lastTradingDayOnOrBefore(d0.minusDays(1)).orElse(null);
        LocalDate lastAvailable = lastAvailable(link);
        if (baselineDate == null || lastAvailable == null) {
            fail(link, INPUT_INCOMPLETE, "사후 구간의 경계를 달력에서 찾지 못했습니다.");
            return;
        }

        Optional<RunSlice> found = runs.readSlice(link.runId(), baselineDate, d0, lastAvailable);
        if (found.isEmpty() || !found.get().fillsRecorded()) {
            // 체결 기록이 없는 실행은 구간 비용을 세울 수 없다(계약 "구간 입력의 출처와 제약").
            // 값을 0으로 채우지 않는다 — 비용이 정말 0인 구간과 구분되지 않는다.
            fail(link, INPUT_INCOMPLETE, "입력 실행에서 구간을 되살릴 수 없습니다.");
            return;
        }
        RunSlice slice = found.get();
        if (oversized(slice.inputs())) {
            fail(link, INPUT_TOO_LARGE, "구간 입력이 계약 상한을 넘습니다.");
            return;
        }

        int attempt = link.attempt() + 1;
        RegimeLinkJob job = job(link, slice, baselineDate, lastAvailable, attempt);
        links.recordPublished(
                link.id(), messageId(link.linkId().value(), attempt), attempt, Instant.now(clock));
        // 발행이 확인되지 않으면 예외가 올라와 위 기록이 되돌아간다 — 큐에 없는 링크가 QUEUED 로
        // 남으면 사용자는 영영 기다린다.
        publisher.publish(job);
        log.info("regime link job published: linkId={} attempt={}", link.linkId().value(), attempt);
    }

    /**
     * 실행을 기다리는 링크를 한 건 정리한다 (T033).
     *
     * <p>입력 실행이 끝났으면 조립해 발행하고, 실패·취소로 끝났으면 {@code FAILED(RUN_FAILED)}다. 자동으로 다시 실행하지 않는다 — 다시 시도할지는
     * 사용자가 정한다(Edge Cases).
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void settlePending(RegimeLink link) {
        RunProgressView progress = runs.readProgress(link.runId()).orElse(null);
        if (progress == null) {
            return;
        }
        if (progress.deadEnd()) {
            links.markFailed(
                    link.id(),
                    new LinkFailure(LinkFailure.RUN_FAILED, "기다리던 분석이 끝나지 못했습니다."),
                    Instant.now(clock));
            return;
        }
        if (progress.completed()) {
            publish(link.id());
        }
    }

    /** 발행기와 같은 규칙으로 짓는다. 저장 컬럼과 메시지 속성이 어긋나면 DLQ 집계가 어느 시도의 실패인지 가릴 수 없다. */
    public static String messageId(String linkId, int attempt) {
        return "RLJ-" + linkId + "-" + attempt;
    }

    /**
     * 이 링크가 실제로 계산할 수 있는 마지막 거래일.
     *
     * <p>판정({@link RegimeLink#availability()})이 들고 있는 확보 거래일 수로 센다 — 구간 안 상장폐지면 시장 달력의 {@code
     * lastAvailable}보다 짧고, 그 차이를 무시하면 폐지 뒤 구간까지 담아 보내게 된다(research R4).
     */
    private LocalDate lastAvailable(RegimeLink link) {
        int completed = link.availability().completedTradingDays();
        if (completed < PostWindow.MIN_TRADING_DAYS) {
            return null;
        }
        return calendar.nthTradingDayFrom(link.postWindow().d0(), completed).orElse(null);
    }

    private boolean oversized(RegimeLinkJob.InputsSpec inputs) {
        if (inputs.portfolioSeries().size() > limits.maxRows()) {
            return true;
        }
        if (inputs.openingPositions().size() > limits.maxInstruments()) {
            return true;
        }
        return inputs.dailyPositions().stream()
                .anyMatch(day -> day.positions().size() > limits.maxInstruments());
    }

    private RegimeLinkJob job(
            RegimeLink link,
            RunSlice slice,
            LocalDate baselineDate,
            LocalDate lastAvailable,
            int attempt) {
        PostWindow window = link.postWindow();
        int completed = link.availability().completedTradingDays();
        RegimeLinkJob.WindowSpec windowSpec =
                new RegimeLinkJob.WindowSpec(
                        window.d0(),
                        window.d21(),
                        // 폐지로 구간이 줄었으면 d63 은 그 구간 밖이다. 달력 값을 그대로 실으면
                        // 워커가 계산할 수 없는 날짜를 주 구간의 끝으로 본다.
                        window.d63() != null && !window.d63().isAfter(lastAvailable)
                                ? window.d63()
                                : null,
                        lastAvailable,
                        completed,
                        baselineDate);
        RegimeLinkScenario scenario = RegimeLinkScenario.valueOf(link.scenario().name());
        String digest = digests.calculate(slice.inputs(), windowSpec, scenario, slice.marketData());
        return new RegimeLinkJob(
                new RegimeLinkJob.JobSpec(link.linkId().value(), attempt, scenario),
                windowSpec,
                withAnalytics(slice),
                universe(slice),
                slice.marketData(),
                slice.inputs(),
                digest);
    }

    private static RegimeLinkJob.RunSpec withAnalytics(RunSlice slice) {
        RegimeLinkJob.RunSpec run = slice.run();
        return new RegimeLinkJob.RunSpec(
                run.runId(), run.effectiveStart(), run.initialCash(), slice.analyticsMethod());
    }

    /** 구간 안에서 폐지되는 종목의 폐지일을 실어 준다. 워커는 그 전날까지만 계산하고 {@code partial=true}를 돌려준다. */
    private RegimeLinkJob.UniverseSpec universe(RunSlice slice) {
        List<RegimeLinkJob.UniverseItem> items =
                slice.universe().items().stream()
                        .map(
                                item ->
                                        new RegimeLinkJob.UniverseItem(
                                                item.sectorId(),
                                                item.instrumentId(),
                                                calendar.delistedOn(item.instrumentId())
                                                        .orElse(null)))
                        .toList();
        return new RegimeLinkJob.UniverseSpec(slice.universe().mappingVersion(), items);
    }

    private void fail(RegimeLink link, String code, String message) {
        log.warn("regime link job not published: linkId={} code={}", link.linkId().value(), code);
        links.markFailed(link.id(), new LinkFailure(code, message), Instant.now(clock));
    }
}
