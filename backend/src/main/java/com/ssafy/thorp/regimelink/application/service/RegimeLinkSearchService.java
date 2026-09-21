package com.ssafy.thorp.regimelink.application.service;

import com.ssafy.thorp.common.exception.BusinessException;
import com.ssafy.thorp.common.exception.ErrorCode;
import com.ssafy.thorp.regimelink.application.command.AvailabilityQuery;
import com.ssafy.thorp.regimelink.application.command.CreateLinkSearchCommand;
import com.ssafy.thorp.regimelink.application.port.BundleRunView;
import com.ssafy.thorp.regimelink.application.repository.RegimeLinkFilterRepository;
import com.ssafy.thorp.regimelink.application.repository.RegimeLinkSearchRepository;
import com.ssafy.thorp.regimelink.application.repository.RegimeSearchReadRepository;
import com.ssafy.thorp.regimelink.application.repository.RegimeSearchView;
import com.ssafy.thorp.regimelink.application.result.AvailabilityResult;
import com.ssafy.thorp.regimelink.application.result.LinkSearchResult;
import com.ssafy.thorp.regimelink.domain.model.RegimeLinkSearch;
import com.ssafy.thorp.regimelink.domain.model.Retention;
import com.ssafy.thorp.regimelink.domain.model.ShownCase;
import com.ssafy.thorp.regimelink.domain.vo.LinkRole;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 사용자가 본 목록을 확정해 기록하고 비교 묶음 변경을 알린다 (T051·T075, contracts §1b).
 *
 * <p><b>여기서는 계산을 만들지 않는다.</b> 큐에 들어가는 것은 사례를 열 때뿐이고(§2), 이 경로는 "무엇을 찾아 무엇이 보였는가"를 남긴다 — 0건이어도 저장한다.
 * 무엇도 나오지 않았다는 사실이 기록이다.
 *
 * <p>변경 알림을 <b>사례를 열 때로 미루지 않는다</b>(T075). 사용자가 아무 사례도 열지 않아도 후보가 바뀐 사실은 알려야 하고, 미루면 열지 않은 검색의 변경이
 * 영영 기록되지 않는다.
 */
@Service
public class RegimeLinkSearchService {

    private final AvailabilityService availability;
    private final RegimeSearchReadRepository snapshots;
    private final RegimeLinkSearchRepository searches;
    private final RegimeLinkFilterRepository filters;

    public RegimeLinkSearchService(
            AvailabilityService availability,
            RegimeSearchReadRepository snapshots,
            RegimeLinkSearchRepository searches,
            RegimeLinkFilterRepository filters) {
        this.availability = availability;
        this.snapshots = snapshots;
        this.searches = searches;
        this.filters = filters;
    }

    @Transactional
    public LinkSearchResult create(CreateLinkSearchCommand command) {
        AvailabilityResult evaluated = availability.evaluate(query(command));
        Long filterId = filterId(evaluated);
        List<BundleRunView> runs = evaluated.bundle().runs();

        Optional<RegimeLinkSearch> existing =
                searches.findByClientRequest(command.ownerUserId(), command.clientRequestId());
        if (existing.isPresent()) {
            // 멱등 키 재사용. 본문이 같으면 기존 기록을 그대로 돌려주고 새 행을 만들지 않는다.
            RegimeLinkSearch found = existing.get();
            requireSameBody(found, command, runs);
            return new LinkSearchResult(
                    found.searchId(), evaluated, change(found.previousSearchId(), found), false);
        }

        RegimeLinkSearch previous = previous(command);
        boolean bundleChanged = previous != null && !Objects.equals(previous.filterId(), filterId);
        List<ShownCase> shown = shownCases(evaluated);
        Retention retention = bundleChanged ? retention(previous, shown) : null;

        RegimeSearchView snapshot = snapshots.findByKey(command.snapshotKey()).orElse(null);
        RegimeLinkSearch saved =
                searches.save(
                        new RegimeLinkSearch(
                                null,
                                null,
                                command.ownerUserId(),
                                command.entryPath(),
                                snapshot == null ? null : snapshot.snapshotId(),
                                command.snapshotKey(),
                                evaluated.search().mode(),
                                evaluated.search().fieldId(),
                                evaluated.search().baselineMonth(),
                                runId(runs, LinkRole.BASE),
                                runId(runs, LinkRole.COMPARE),
                                requestId(runs, LinkRole.BASE),
                                requestId(runs, LinkRole.COMPARE),
                                filterId,
                                shown,
                                excludedCount(evaluated),
                                command.fullSearchShown(),
                                bundleChanged ? previous.id() : null,
                                retention,
                                RegimeLinkSearch.BASIS_HISTORICAL_REVISION,
                                command.clientRequestId(),
                                null));

        return new LinkSearchResult(
                saved.searchId(),
                evaluated,
                bundleChanged
                        ? new LinkSearchResult.BundleChange(
                                retention.added(), retention.removed(), excludedCount(evaluated))
                        : null,
                true);
    }

    /** 저장된 기록에서 변경 알림을 되살린다. 멱등 재시도가 처음과 같은 응답을 내게 하는 자리다. */
    private static LinkSearchResult.BundleChange change(
            Long previousSearchId, RegimeLinkSearch found) {
        if (previousSearchId == null || found.retention() == null) {
            return null;
        }
        return new LinkSearchResult.BundleChange(
                found.retention().added(), found.retention().removed(), found.excludedCount());
    }

    private static AvailabilityQuery query(CreateLinkSearchCommand command) {
        return new AvailabilityQuery(
                command.base(),
                command.compare(),
                command.snapshotKey(),
                null,
                null,
                null,
                command.entryPath());
    }

    /**
     * 같은 멱등 키에 다른 본문이 오면 {@code 400 INVALID_INPUT}.
     *
     * <p>조용히 기존 기록을 돌려주면 사용자는 방금 보낸 조건이 저장된 줄 안다 — 키 재사용은 클라이언트의 실수이고 드러내는 편이 낫다.
     */
    private static void requireSameBody(
            RegimeLinkSearch found, CreateLinkSearchCommand command, List<BundleRunView> runs) {
        boolean same =
                Objects.equals(found.snapshotKey(), command.snapshotKey())
                        && found.entryPath() == command.entryPath()
                        && Objects.equals(found.baseRunId(), runId(runs, LinkRole.BASE))
                        && Objects.equals(found.compareRunId(), runId(runs, LinkRole.COMPARE))
                        && found.fullSearchShown() == command.fullSearchShown();
        if (!same) {
            throw new BusinessException(
                    ErrorCode.INVALID_INPUT, "같은 clientRequestId 로 다른 조건이 왔습니다.");
        }
    }

    /**
     * 직전 기록. 같은 요청자·같은 snapshot·같은 경로여야 한다.
     *
     * <p>다르면 404 다 — 다른 탭이나 다른 흐름의 기록을 임의로 골라 견주면 사용자가 보지 않은 목록과의 차이를 알림으로 받는다(contracts §1b).
     */
    private RegimeLinkSearch previous(CreateLinkSearchCommand command) {
        if (command.previousLinkSearchId() == null) {
            return null;
        }
        RegimeLinkSearch found =
                searches.findBySearchId(command.previousLinkSearchId())
                        .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        if (found.ownerUserId() != command.ownerUserId()
                || !Objects.equals(found.snapshotKey(), command.snapshotKey())
                || found.entryPath() != command.entryPath()) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        }
        return found;
    }

    /**
     * 유지율과 들고 난 사례 (data-model §3 {@code retention}).
     *
     * <p><b>비율은 응답에 싣지 않는다</b>(FR-021) — 기록 전용이다. 여기서 세는 것은 기본 목록에 실제로 보인 사례다.
     */
    private static Retention retention(RegimeLinkSearch previous, List<ShownCase> current) {
        Set<YearMonth> before = months(previous.shownCases());
        Set<YearMonth> after = months(current);
        List<YearMonth> kept = new ArrayList<>(before);
        kept.retainAll(after);
        List<YearMonth> added = new ArrayList<>(after);
        added.removeAll(before);
        List<YearMonth> removed = new ArrayList<>(before);
        removed.removeAll(after);
        BigDecimal ratio =
                before.isEmpty()
                        ? null
                        : BigDecimal.valueOf(kept.size())
                                .divide(BigDecimal.valueOf(before.size()), 4, RoundingMode.HALF_UP);
        return new Retention(ratio, kept, added, removed);
    }

    private static Set<YearMonth> months(List<ShownCase> cases) {
        Set<YearMonth> months = new LinkedHashSet<>();
        cases.forEach(shown -> months.add(shown.endMonth()));
        return months;
    }

    /** 기록에 남기는 것은 <b>그때 보인 사례</b>다. 필터로 빠진 사례는 담지 않는다(data-model §3). */
    private static List<ShownCase> shownCases(AvailabilityResult evaluated) {
        return evaluated.cases().stream()
                .filter(AvailabilityResult.CaseAvailability::shownInFiltered)
                .map(
                        found ->
                                new ShownCase(
                                        found.endMonth(),
                                        found.rank(),
                                        found.availability(),
                                        List.of()))
                .toList();
    }

    private static int excludedCount(AvailabilityResult evaluated) {
        Integer excluded = evaluated.filter().excludedCount();
        return excluded == null ? 0 : excluded;
    }

    private Long filterId(AvailabilityResult evaluated) {
        if (evaluated.filter().key() == null) {
            return null;
        }
        return filters.findByKey(evaluated.filter().key()).map(found -> found.id()).orElse(null);
    }

    private static Long runId(List<BundleRunView> runs, LinkRole role) {
        return runs.stream()
                .filter(run -> run.role() == role)
                .map(BundleRunView::runId)
                .findFirst()
                .orElse(null);
    }

    private static Long requestId(List<BundleRunView> runs, LinkRole role) {
        return runs.stream()
                .filter(run -> run.role() == role)
                .map(BundleRunView::requestId)
                .findFirst()
                .orElse(null);
    }
}
