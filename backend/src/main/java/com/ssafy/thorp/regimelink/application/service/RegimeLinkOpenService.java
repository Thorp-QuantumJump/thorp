package com.ssafy.thorp.regimelink.application.service;

import com.ssafy.thorp.common.exception.BusinessException;
import com.ssafy.thorp.common.exception.ErrorCode;
import com.ssafy.thorp.regimelink.application.command.AvailabilityQuery;
import com.ssafy.thorp.regimelink.application.command.OpenRegimeLinkCommand;
import com.ssafy.thorp.regimelink.application.port.BacktestAcceptancePort;
import com.ssafy.thorp.regimelink.application.port.BacktestRunReadPort;
import com.ssafy.thorp.regimelink.application.port.BundleRunView;
import com.ssafy.thorp.regimelink.application.port.RunSlice;
import com.ssafy.thorp.regimelink.application.port.TradingCalendarPort;
import com.ssafy.thorp.regimelink.application.repository.RegimeLinkRepository;
import com.ssafy.thorp.regimelink.application.repository.RegimeLinkSearchRepository;
import com.ssafy.thorp.regimelink.application.repository.RegimeLinkUseRepository;
import com.ssafy.thorp.regimelink.application.result.AvailabilityResult;
import com.ssafy.thorp.regimelink.application.result.OpenLinkResult;
import com.ssafy.thorp.regimelink.domain.model.Availability;
import com.ssafy.thorp.regimelink.domain.model.PostWindow;
import com.ssafy.thorp.regimelink.domain.model.RegimeLink;
import com.ssafy.thorp.regimelink.domain.model.RegimeLinkSearch;
import com.ssafy.thorp.regimelink.domain.model.RegimeLinkUse;
import com.ssafy.thorp.regimelink.domain.vo.AvailabilityState;
import com.ssafy.thorp.regimelink.domain.vo.CaseRef;
import com.ssafy.thorp.regimelink.domain.vo.LinkStatus;
import com.ssafy.thorp.regimelink.domain.vo.Scenario;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 사례 하나의 전략 결과를 연다 (T039·T058·T067·T068·T092, contracts §2).
 *
 * <p>비교 묶음의 실행마다 {@code regime_link} 행을 <b>찾거나</b> 만들고, 이 검색이 그 계산을 열었다는 {@link RegimeLinkUse}를
 * 남긴다. 링크 행은 여러 사용자가 공유하므로 두 사용자가 같은 계산을 열면 링크 하나에 이용 기록 둘이다(research R14).
 *
 * <p><b>확인이 필요한 경우에는 행도 메시지도 만들지 않는다.</b> 분석 기간이 사례를 덮지 않거나(§2) 입력 실행이 개별 체결을 남기지 않았으면(T092) 먼저 확인을
 * 받고, 확인 뒤에 004 에 새 실행을 접수한다.
 *
 * <p>작업 발행은 <b>커밋 뒤</b>다 — 트랜잭션 안에서 보내면 워커의 {@code STARTED}가 커밋을 앞질러 도착해 아직 없는 링크를 갱신하려 든다(004
 * R-18과 같은 이유).
 */
@Service
public class RegimeLinkOpenService {

    private final AvailabilityService availability;
    private final RegimeLinkSearchRepository searches;
    private final RegimeLinkRepository links;
    private final RegimeLinkUseRepository uses;
    private final BacktestRunReadPort runs;
    private final BacktestAcceptancePort acceptance;
    private final TradingCalendarPort calendar;
    private final ApplicationEventPublisher events;

    public RegimeLinkOpenService(
            AvailabilityService availability,
            RegimeLinkSearchRepository searches,
            RegimeLinkRepository links,
            RegimeLinkUseRepository uses,
            BacktestRunReadPort runs,
            BacktestAcceptancePort acceptance,
            TradingCalendarPort calendar,
            ApplicationEventPublisher events) {
        this.availability = availability;
        this.searches = searches;
        this.links = links;
        this.uses = uses;
        this.runs = runs;
        this.acceptance = acceptance;
        this.calendar = calendar;
        this.events = events;
    }

    /**
     * 004 접수({@code BacktestService.create})가 {@code BusinessException}으로 거절할 수 있다 — 대기열 상한(429)이
     * 그것이다. 기본 롤백 규칙이면 그 프록시가 바깥 트랜잭션을 rollback-only 로 표시해 정상 응답이 500 이 된다(backend/CLAUDE.md).
     */
    @Transactional(noRollbackFor = BusinessException.class)
    public OpenLinkResult open(OpenRegimeLinkCommand command) {
        RegimeLinkSearch search = requireOwnSearch(command);
        AvailabilityResult evaluated =
                availability.evaluate(
                        new AvailabilityQuery(
                                null,
                                null,
                                search.snapshotKey(),
                                null,
                                null,
                                null,
                                search.entryPath()),
                        bundle(search));
        AvailabilityResult.CaseAvailability found = requireCase(evaluated, command.caseRef());

        List<BundleRunView> bundle = evaluated.bundle().runs();
        List<OpenLinkResult.Affected> outOfPeriod = outOfPeriod(found, bundle);
        if (!outOfPeriod.isEmpty() && !command.confirmFullPeriod()) {
            return OpenLinkResult.confirmRequired(
                    OpenLinkResult.Outcome.OUT_OF_PERIOD_CONFIRM_REQUIRED,
                    requiredRange(found),
                    outOfPeriod);
        }
        List<OpenLinkResult.Affected> unrecorded = withoutFills(found, bundle);
        if (!unrecorded.isEmpty() && !command.confirmFullPeriod()) {
            return OpenLinkResult.confirmRequired(
                    OpenLinkResult.Outcome.FILLS_MISSING_CONFIRM_REQUIRED,
                    requiredRange(found),
                    unrecorded);
        }

        List<OpenLinkResult.AcceptedLink> accepted = new ArrayList<>(bundle.size());
        for (BundleRunView run : bundle) {
            accepted.add(openOne(command, search, found, run));
        }
        return OpenLinkResult.accepted(search.searchId(), accepted);
    }

    /**
     * 실행 하나의 링크를 찾거나 만든다.
     *
     * <p>이미 있는 계산은 다시 만들지 않는다 — {@code UNIQUE (run_id, snapshot_key, end_month, scenario)}가 그 규칙을
     * 스키마로 강제하므로 여기서도 먼저 찾는다(FR-010).
     */
    private OpenLinkResult.AcceptedLink openOne(
            OpenRegimeLinkCommand command,
            RegimeLinkSearch search,
            AvailabilityResult.CaseAvailability found,
            BundleRunView run) {
        Availability judged = judged(found, run);
        Long inputRunId = run.runId();
        Long createdRequestId = null;
        LinkStatus status = LinkStatus.QUEUED;

        if (judged.state().excludedByFilter()) {
            // 자료 부족·사후 미완성은 계산 작업을 만들지 않는 판정이다. 행만 남겨 화면이 사유를
            // 보이게 한다(FR-018) — 실패와 섞지 않는다.
            //
            // **이 갈래를 먼저 본다.** 계산하지 않기로 한 사례에 004 재계산을 접수하면, 결과가
            // 있어도 볼 수 없는 구간을 위해 실행을 하나 더 만들게 된다.
            status = LinkStatus.COMPLETED;
        } else if (judged.state() == AvailabilityState.OUT_OF_PERIOD
                || command.scenario() == Scenario.FRESH_CASH
                || !hasFills(run, found)) {
            BacktestAcceptancePort.AcceptedRun derived = derive(command, found, run);
            inputRunId = derived.runId();
            createdRequestId = derived.requestId();
            status = derived.completed() ? LinkStatus.QUEUED : LinkStatus.PENDING_RUN;
            // 새 실행은 사례 구간을 덮도록 접수했으므로 기간 밖 판정이 남지 않는다. 링크에
            // OUT_OF_PERIOD 를 저장하지 않는 것이 data-model §4 의 불변식이다.
            judged = withoutOutOfPeriod(judged);
        }

        RegimeLink link = findOrCreate(command, found, inputRunId, judged, status);
        // 한 검색이 같은 계산을 두 번 열어도 이용 기록은 하나다({@code UNIQUE (link_search_id,
        // link_id)}). 화면이 같은 사례를 다시 누르는 것은 흔한 일이라 그것을 오류로 올리지 않는다.
        boolean alreadyUsed =
                uses.findByLinkId(link.id()).stream()
                        .anyMatch(use -> Objects.equals(use.linkSearchId(), search.id()));
        if (!alreadyUsed) {
            uses.save(
                    RegimeLinkUse.create(
                            search.id(),
                            link.id(),
                            run.role(),
                            run.runId(),
                            run.requestId(),
                            createdRequestId));
        }
        if (link.status() == LinkStatus.QUEUED && link.attempt() == 0) {
            events.publishEvent(new RegimeLinkQueued(link.id()));
        }
        return new OpenLinkResult.AcceptedLink(
                link.linkId(), run.publicToken(), link.scenario(), link.status());
    }

    private RegimeLink findOrCreate(
            OpenRegimeLinkCommand command,
            AvailabilityResult.CaseAvailability found,
            Long runId,
            Availability judged,
            LinkStatus status) {
        Optional<RegimeLink> existing =
                links.findUnit(runId, command.caseRef(), command.scenario());
        if (existing.isPresent()) {
            return existing.get();
        }
        PostWindow window = found.postWindow();
        RegimeLink created =
                status == LinkStatus.COMPLETED
                        ? RegimeLink.dataInsufficientOrIncomplete(
                                runId, command.caseRef(), command.scenario(), window, judged)
                        : RegimeLink.pending(
                                runId,
                                command.caseRef(),
                                command.scenario(),
                                window,
                                judged,
                                status);
        return links.create(created);
    }

    /** 확인 뒤에 004 로 접수하는 파생 실행. 시나리오가 무엇을 만들지 정한다. */
    private BacktestAcceptancePort.AcceptedRun derive(
            OpenRegimeLinkCommand command,
            AvailabilityResult.CaseAvailability found,
            BundleRunView run) {
        PostWindow window = found.postWindow();
        LocalDate end = window.d63() == null ? window.lastAvailable() : window.d63();
        if (command.scenario() == Scenario.FRESH_CASH) {
            LocalDate start = warmupStart(window.d0(), run.warmupTradingDays());
            return acceptance.acceptFreshCash(
                    run.runId(), command.requesterId(), start, window.d0(), end);
        }
        OpenLinkResult.Period required = requiredRange(found);
        return acceptance.acceptFullPeriod(
                run.runId(), command.requesterId(), required.start(), required.end());
    }

    /** {@code d0} 에서 워밍업 거래일만큼 거슬러 올라간 첫날. 달력이 그만큼 닿지 않으면 {@code d0} 그대로다. */
    private LocalDate warmupStart(LocalDate d0, int warmupTradingDays) {
        if (warmupTradingDays <= 0) {
            return d0;
        }
        LocalDate cursor = d0;
        for (int back = 0; back < warmupTradingDays; back++) {
            LocalDate previous =
                    calendar.lastTradingDayOnOrBefore(cursor.minusDays(1)).orElse(null);
            if (previous == null) {
                return cursor;
            }
            cursor = previous;
        }
        return cursor;
    }

    private RegimeLinkSearch requireOwnSearch(OpenRegimeLinkCommand command) {
        RegimeLinkSearch search =
                searches.findBySearchId(command.linkSearchId())
                        .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        if (search.ownerUserId() != command.requesterId()) {
            // 남의 기록은 403 이 아니라 404 로 숨긴다(004 R-12와 같은 규칙).
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        }
        return search;
    }

    private List<BundleRunView> bundle(RegimeLinkSearch search) {
        return runs.resolveBundleByRequest(
                search.baseRunId(),
                search.baseRequestId(),
                search.compareRunId(),
                search.compareRequestId());
    }

    /** 표시 후보 밖 사례는 열 수 없다(contracts §2 오류표 404). */
    private static AvailabilityResult.CaseAvailability requireCase(
            AvailabilityResult evaluated, CaseRef caseRef) {
        return evaluated.cases().stream()
                .filter(found -> found.caseRef().equals(caseRef))
                .findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
    }

    private static Availability judged(
            AvailabilityResult.CaseAvailability found, BundleRunView run) {
        return found.perRun().stream()
                .filter(
                        perRun ->
                                perRun.publicToken() != null
                                        ? perRun.publicToken().equals(run.publicToken())
                                        : false)
                .map(AvailabilityResult.RunAvailability::availability)
                .findFirst()
                .orElse(found.availability());
    }

    private static Availability withoutOutOfPeriod(Availability judged) {
        return judged.state() == AvailabilityState.OUT_OF_PERIOD
                ? Availability.normal(judged.completedTradingDays())
                : judged;
    }

    private static List<OpenLinkResult.Affected> outOfPeriod(
            AvailabilityResult.CaseAvailability found, List<BundleRunView> bundle) {
        List<OpenLinkResult.Affected> affected = new ArrayList<>();
        for (BundleRunView run : bundle) {
            if (judged(found, run).state() == AvailabilityState.OUT_OF_PERIOD) {
                affected.add(
                        new OpenLinkResult.Affected(
                                run.publicToken(),
                                new OpenLinkResult.Period(run.start(), run.end())));
            }
        }
        return affected;
    }

    /**
     * 체결 기록이 없는 실행 (T092).
     *
     * <p>'분석 기간 밖'과 <b>같은 확인 흐름</b>으로 다룬다 — 확인 전에는 링크도 메시지도 만들지 않고, 확인하면 같은 조건의 새 실행을 요청한다. 값을 추정해
     * 채우지 않는다.
     */
    private List<OpenLinkResult.Affected> withoutFills(
            AvailabilityResult.CaseAvailability found, List<BundleRunView> bundle) {
        List<OpenLinkResult.Affected> affected = new ArrayList<>();
        for (BundleRunView run : bundle) {
            Availability judged = judged(found, run);
            if (judged.state() == AvailabilityState.NORMAL && !hasFills(run, found)) {
                affected.add(
                        new OpenLinkResult.Affected(
                                run.publicToken(),
                                new OpenLinkResult.Period(run.start(), run.end())));
            }
        }
        return affected;
    }

    private boolean hasFills(BundleRunView run, AvailabilityResult.CaseAvailability found) {
        if (!run.completed()) {
            return true; // 아직 끝나지 않은 실행은 체결 여부를 물을 수 없다. 기다린 뒤 발행 관문이 본다.
        }
        PostWindow window = found.postWindow();
        LocalDate d0 = window.d0();
        LocalDate baselineDate = calendar.lastTradingDayOnOrBefore(d0.minusDays(1)).orElse(d0);
        LocalDate lastAvailable = window.lastAvailable() == null ? d0 : window.lastAvailable();
        return runs.readSlice(run.runId(), baselineDate, d0, lastAvailable)
                .map(RunSlice::fillsRecorded)
                .orElse(false);
    }

    /** 사례를 보려면 실행이 덮어야 하는 구간. 확인 화면이 "언제부터 언제까지"를 보인다. */
    private OpenLinkResult.Period requiredRange(AvailabilityResult.CaseAvailability found) {
        PostWindow window = found.postWindow();
        LocalDate start =
                calendar.lastTradingDayOnOrBefore(window.d0().minusDays(1)).orElse(window.d0());
        LocalDate end = window.lastAvailable() == null ? window.d0() : window.lastAvailable();
        return new OpenLinkResult.Period(start, end);
    }

    /** 커밋 뒤에 작업을 발행하라는 신호. 트랜잭션 안에서 보내면 워커가 커밋을 앞지른다. */
    public record RegimeLinkQueued(Long linkDbId) {}
}
