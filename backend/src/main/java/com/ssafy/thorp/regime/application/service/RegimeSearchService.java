package com.ssafy.thorp.regime.application.service;

import com.ssafy.thorp.regime.application.exception.RegimeSearchNotFoundException;
import com.ssafy.thorp.regime.application.port.FieldRegistration;
import com.ssafy.thorp.regime.application.port.IndicatorRegistration;
import com.ssafy.thorp.regime.application.port.MacroIndicatorPort;
import com.ssafy.thorp.regime.application.repository.RegimeBaselineMonthRepository;
import com.ssafy.thorp.regime.application.repository.RegimeSearchLogRepository;
import com.ssafy.thorp.regime.application.repository.RegimeSnapshotRepository;
import com.ssafy.thorp.regime.application.result.BaselineMonthsResult;
import com.ssafy.thorp.regime.application.result.RegimeCaseResult;
import com.ssafy.thorp.regime.application.result.RegimeSearchResult;
import com.ssafy.thorp.regime.domain.model.RegimeBaselineMonth;
import com.ssafy.thorp.regime.domain.model.RegimeCase;
import com.ssafy.thorp.regime.domain.model.RegimeSnapshot;
import com.ssafy.thorp.regime.domain.vo.BaselineOrigin;
import com.ssafy.thorp.regime.domain.vo.SearchMode;
import com.ssafy.thorp.regime.domain.vo.SnapshotKey;
import java.time.YearMonth;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * 사전 계산된 검색 결과를 읽어 내보낸다 (spec FR-030).
 *
 * <p><b>척도도 거리도 여기서 계산하지 않는다.</b> 이 클래스가 하는 일은 셋뿐이다 — 어느 행을 읽을지 정하고, 저장된 값을 꺼내고, 표시용 이름을 등록표에서 붙인다.
 * 008에서 읽는 것은 포트의 등록표뿐이고 지표 시계열에는 닿지 않는다(계약 §0.1, SC-002a).
 *
 * <p>조회는 그 (모드·분야·기준월)의 <b>가장 큰 revision</b>을 읽는다. 006 기록이 고정한 항목은 {@code searchId}로 그 행을 지목해 연다.
 */
@Service
public class RegimeSearchService {

    /** 화면이 고를 수 있는 표시 개수 (계약 §1). 처음 3개, '더 보기'면 10개다. */
    private static final List<Integer> ALLOWED_LIMITS = List.of(3, 10);

    private final RegimeSnapshotRepository snapshots;
    private final RegimeBaselineMonthRepository baselineMonths;
    private final RegimeSearchLogRepository searchLogs;
    private final MacroIndicatorPort registry;

    public RegimeSearchService(
            RegimeSnapshotRepository snapshots,
            RegimeBaselineMonthRepository baselineMonths,
            RegimeSearchLogRepository searchLogs,
            MacroIndicatorPort registry) {
        this.snapshots = snapshots;
        this.baselineMonths = baselineMonths;
        this.searchLogs = searchLogs;
        this.registry = registry;
    }

    // -- §1 검색 하나 ----------------------------------------------------------------

    public RegimeSearchResult search(
            SearchMode mode, String fieldId, YearMonth requestedMonth, int limit) {
        requireAllowedLimit(limit);
        FieldRegistration field = resolveField(mode, fieldId);
        YearMonth baselineMonth = resolveBaselineMonth(mode, fieldId, requestedMonth);
        RegimeSnapshot snapshot = requireLatest(mode, fieldId, baselineMonth);
        return view(snapshot, BaselineOrigin.of(mode, requestedMonth != null), field, limit);
    }

    // -- §2 고정된 항목 --------------------------------------------------------------

    public RegimeSearchResult searchById(String searchId, int limit) {
        requireAllowedLimit(limit);
        SnapshotKey key = SnapshotKey.parse(searchId);
        RegimeSnapshot snapshot = requireByKey(searchId);
        FieldRegistration field = resolveField(key.mode(), key.fieldId());
        // 기록이 고정한 항목을 연 것이므로 근거는 늘 '사용자가 지목함'이다 — 그때 어떤 근거로
        // 얻었는지는 기록(regime_search_log.baseline_origin)이 들고 있고 이 응답의 몫이 아니다.
        return view(snapshot, BaselineOrigin.USER_SPECIFIED, field, limit);
    }

    // -- §3 사례 상세 ----------------------------------------------------------------

    public RegimeCaseResult caseDetail(
            SearchMode mode, String fieldId, YearMonth requestedMonth, YearMonth endMonth) {
        resolveField(mode, fieldId);
        YearMonth baselineMonth = resolveBaselineMonth(mode, fieldId, requestedMonth);
        return caseOf(requireLatest(mode, fieldId, baselineMonth), endMonth);
    }

    public RegimeCaseResult caseDetailById(String searchId, YearMonth endMonth) {
        return caseOf(requireByKey(searchId), endMonth);
    }

    private RegimeCaseResult caseOf(RegimeSnapshot snapshot, YearMonth endMonth) {
        RegimeCase found =
                snapshot.cases().stream()
                        .filter(item -> item.endMonth().equals(endMonth))
                        .findFirst()
                        .orElseThrow(
                                () ->
                                        new RegimeSearchNotFoundException(
                                                "그 검색에 해당 사례가 없습니다: " + endMonth));
        return new RegimeCaseResult(snapshot, found, indicatorNames(snapshot));
    }

    // -- §4 전체 유효 후보 -----------------------------------------------------------

    /**
     * 011이 가용성 필터를 얹을 원본 순위 목록.
     *
     * <p><b>010은 이 목록에 필터도 ±6개월 건너뛰기도 적용하지 않는다</b>(spec FR-012·FR-030). 표시 후보는 여기 담지 않는다 — 화면 목록은
     * §1의 {@code cases}를 쓴다.
     */
    public RegimeSearchResult candidates(
            SearchMode mode, String fieldId, YearMonth requestedMonth) {
        FieldRegistration field = resolveField(mode, fieldId);
        YearMonth baselineMonth = resolveBaselineMonth(mode, fieldId, requestedMonth);
        RegimeSnapshot snapshot = requireLatest(mode, fieldId, baselineMonth);
        return new RegimeSearchResult(
                snapshot,
                BaselineOrigin.of(mode, requestedMonth != null),
                field,
                indicatorNames(snapshot),
                List.of());
    }

    public RegimeSearchResult candidatesById(String searchId) {
        SnapshotKey key = SnapshotKey.parse(searchId);
        RegimeSnapshot snapshot = requireByKey(searchId);
        return new RegimeSearchResult(
                snapshot,
                BaselineOrigin.USER_SPECIFIED,
                resolveField(key.mode(), key.fieldId()),
                indicatorNames(snapshot),
                List.of());
    }

    // -- §5 기준월 목록 --------------------------------------------------------------

    public BaselineMonthsResult baselineMonths() {
        Map<String, RegimeBaselineMonth> recorded = new LinkedHashMap<>();
        for (RegimeBaselineMonth month : baselineMonths.findAll()) {
            recorded.put(month.scopeId(), month);
        }
        List<BaselineMonthsResult.FieldBaseline> byField =
                registry.fields().stream()
                        .sorted(FieldRegistration.DISPLAY_ORDER)
                        .filter(field -> recorded.containsKey(field.id()))
                        .map(
                                field ->
                                        new BaselineMonthsResult.FieldBaseline(
                                                field, recorded.get(field.id()).baselineMonth()))
                        .toList();
        RegimeBaselineMonth composite = recorded.get(SearchMode.COMPOSITE_SCOPE);
        return new BaselineMonthsResult(
                byField,
                composite == null ? null : composite.baselineMonth(),
                earliest(recorded.values()),
                latest(recorded.values()));
    }

    private YearMonth earliest(Iterable<RegimeBaselineMonth> months) {
        YearMonth found = null;
        for (RegimeBaselineMonth month : months) {
            if (found == null || month.earliestMonth().isBefore(found)) {
                found = month.earliestMonth();
            }
        }
        return found;
    }

    private YearMonth latest(Iterable<RegimeBaselineMonth> months) {
        YearMonth found = null;
        for (RegimeBaselineMonth month : months) {
            if (found == null || month.baselineMonth().isAfter(found)) {
                found = month.baselineMonth();
            }
        }
        return found;
    }

    // -- 공통 --------------------------------------------------------------------------

    private RegimeSearchResult view(
            RegimeSnapshot snapshot, BaselineOrigin origin, FieldRegistration field, int limit) {
        // 잘라 내기만 한다. 몇 개를 잘랐는지 세지 않는다 — 그 수는 "그만큼 비슷했다"로 읽힌다.
        List<RegimeCase> displayed = snapshot.cases().stream().limit(limit).toList();
        return new RegimeSearchResult(snapshot, origin, field, indicatorNames(snapshot), displayed);
    }

    private Map<String, IndicatorRegistration> indicatorNames(RegimeSnapshot snapshot) {
        Map<String, IndicatorRegistration> names = new LinkedHashMap<>();
        for (String indicatorId : snapshot.comparisonMonths().keySet()) {
            registry.indicator(indicatorId).ifPresent(entry -> names.put(indicatorId, entry));
        }
        return Map.copyOf(names);
    }

    private void requireAllowedLimit(int limit) {
        if (!ALLOWED_LIMITS.contains(limit)) {
            throw new IllegalArgumentException("limit 은 3 또는 10 이어야 합니다: " + limit);
        }
    }

    /** 분야 검색이면 등록표에서 그 분야를 찾는다. 없으면 404다(계약 §1 오류표). */
    private FieldRegistration resolveField(SearchMode mode, String fieldId) {
        if (mode == SearchMode.COMPOSITE) {
            return null;
        }
        if (fieldId == null || fieldId.isBlank()) {
            throw new IllegalArgumentException("mode=field 에는 field 가 있어야 합니다.");
        }
        return registry.fields().stream()
                .filter(field -> field.id().equals(fieldId))
                .findFirst()
                .orElseThrow(() -> new RegimeSearchNotFoundException("등록표에 없는 분야입니다: " + fieldId));
    }

    /**
     * 기준월을 정한다.
     *
     * <p>요청에 있으면 그것이고, 없으면 <b>배치가 기록한 표</b>에서 읽는다 — 008에 직접 묻지 않는다(계약 §0.2).
     */
    private YearMonth resolveBaselineMonth(
            SearchMode mode, String fieldId, YearMonth requestedMonth) {
        if (requestedMonth != null) {
            return requestedMonth;
        }
        String scopeId = mode.scopeId(fieldId);
        return baselineMonths
                .findByScope(scopeId)
                .map(RegimeBaselineMonth::baselineMonth)
                .orElseThrow(
                        () -> new RegimeSearchNotFoundException("아직 계산된 기준월이 없습니다: " + scopeId));
    }

    private RegimeSnapshot requireLatest(SearchMode mode, String fieldId, YearMonth baselineMonth) {
        return snapshots
                .findLatest(mode, fieldId, baselineMonth)
                .orElseThrow(() -> outOfRange(mode, fieldId, baselineMonth));
    }

    /**
     * 그 기준월의 결과가 없다.
     *
     * <p><b>여기서 404가 되는 것은 자료 범위 밖일 때뿐이다.</b> 범위 안인데 척도가 나오지 않는 달은 배치가 계산 불가 행을 남겨 두므로 200 + {@code
     * NOT_COMPUTABLE}로 나간다(계약 §1, T098). 범위 안인데 행이 없으면 배치가 아직 그 달을 돌지 않은 것이고, 그 사실을 문구로 구분해 알린다 —
     * "자료가 없다"와 "아직 계산하지 않았다"는 사용자가 기다릴지 말지를 가른다.
     */
    private RegimeSearchNotFoundException outOfRange(
            SearchMode mode, String fieldId, YearMonth baselineMonth) {
        String scopeId = mode.scopeId(fieldId);
        Optional<RegimeBaselineMonth> recorded = baselineMonths.findByScope(scopeId);
        if (recorded.isPresent() && inRange(recorded.get(), baselineMonth)) {
            return new RegimeSearchNotFoundException(
                    "그 기준월은 아직 계산되지 않았습니다: " + scopeId + " " + baselineMonth);
        }
        return new RegimeSearchNotFoundException(
                "자료 범위 밖의 기준월입니다: " + scopeId + " " + baselineMonth);
    }

    private boolean inRange(RegimeBaselineMonth recorded, YearMonth baselineMonth) {
        return !baselineMonth.isBefore(recorded.earliestMonth())
                && !baselineMonth.isAfter(recorded.baselineMonth());
    }

    /**
     * 식별 키로 지목한 항목. 없으면 <b>철회와 정리를 구분해</b> 알린다(spec FR-035).
     *
     * <p>정리(FR-029)는 기록이 가리키는 항목을 남기므로, 기록이 있는데 참조가 끊긴 것은 철회뿐이다(data-model §7). 둘을 같은 문구로 말하면 "값을
     * 다시 열 수 없다"는 사실과 "오래된 것이라 지웠다"가 구분되지 않는다.
     */
    private RegimeSnapshot requireByKey(String searchId) {
        return snapshots
                .findByKey(searchId)
                .orElseThrow(
                        () ->
                                searchLogs.isWithdrawn(searchId)
                                        ? new RegimeSearchNotFoundException(
                                                "참조하던 결과가 철회되었습니다: " + searchId)
                                        : new RegimeSearchNotFoundException(
                                                "그 검색 결과는 보관 정리되어 없습니다: " + searchId));
    }
}
