package com.ssafy.thorp.regimelink.infrastructure.regime;

import com.ssafy.thorp.regime.application.repository.RegimeBaselineMonthRepository;
import com.ssafy.thorp.regime.application.repository.RegimeSnapshotRepository;
import com.ssafy.thorp.regime.domain.model.RegimeBaselineMonth;
import com.ssafy.thorp.regime.domain.model.RegimeCase;
import com.ssafy.thorp.regime.domain.model.RegimeSnapshot;
import com.ssafy.thorp.regime.domain.vo.SearchMode;
import com.ssafy.thorp.regimelink.application.repository.CaseCandidate;
import com.ssafy.thorp.regimelink.application.repository.RegimeSearchReadRepository;
import com.ssafy.thorp.regimelink.application.repository.RegimeSearchView;
import java.time.YearMonth;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 010 조회에 위임해 011 모델로 옮긴다 (research R8, T025).
 *
 * <p><b>010 표를 직접 매핑하지 않는다.</b> 010이 소유한 {@code RegimeSnapshotRepository}를 그대로 부르므로 010이 저장 모양을 바꿔도
 * 이 클래스의 위 계층은 흔들리지 않는다. 픽스처 대역과 출처 전환 프로퍼티를 두지 않는다 — 실제 표가 dev에 있는데 대역을 두면 운영 차단 가드와 폐기 절차만 늘어난다.
 */
@Component
public class RegimeSearchReadAdapter implements RegimeSearchReadRepository {

    private final RegimeSnapshotRepository snapshots;
    private final RegimeBaselineMonthRepository baselineMonths;

    public RegimeSearchReadAdapter(
            RegimeSnapshotRepository snapshots, RegimeBaselineMonthRepository baselineMonths) {
        this.snapshots = snapshots;
        this.baselineMonths = baselineMonths;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<RegimeSearchView> findLatest(
            String mode, String fieldId, YearMonth baselineMonth) {
        SearchMode searchMode = SearchMode.from(mode);
        YearMonth month = baselineMonth == null ? defaultMonth(searchMode, fieldId) : baselineMonth;
        if (month == null) {
            return Optional.empty();
        }
        return snapshots.findLatest(searchMode, fieldId, month).map(RegimeSearchReadAdapter::view);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<RegimeSearchView> findByKey(String snapshotKey) {
        return snapshots.findByKey(snapshotKey).map(RegimeSearchReadAdapter::view);
    }

    /**
     * 기준월을 지정하지 않았을 때 010이 정한 기본값(T_g/T_all).
     *
     * <p>011이 "가장 최근 달" 같은 규칙을 새로 만들지 않는다 — 어느 달이 계산돼 있는지는 010 배치가 알고, 그 기록이 {@code
     * regime_baseline_month}다.
     */
    private YearMonth defaultMonth(SearchMode mode, String fieldId) {
        return baselineMonths
                .findByScope(mode.scopeId(fieldId))
                .map(RegimeBaselineMonth::baselineMonth)
                .orElse(null);
    }

    private static RegimeSearchView view(RegimeSnapshot snapshot) {
        return new RegimeSearchView(
                snapshot.id(),
                snapshot.key().toString(),
                snapshot.mode().name(),
                snapshot.fieldId(),
                snapshot.baselineMonth(),
                snapshot.status().name(),
                candidates(snapshot));
    }

    /** 표시 후보를 010 순위 순서 그대로 옮긴다. 순위를 다시 매기거나 잘라 내지 않는다. */
    private static List<CaseCandidate> candidates(RegimeSnapshot snapshot) {
        return snapshot.cases().stream()
                .sorted(Comparator.comparingInt(RegimeCase::rank))
                .map(
                        found ->
                                new CaseCandidate(
                                        found.endMonth(),
                                        found.rank(),
                                        found.distance() == null ? null : found.distance().value()))
                .toList();
    }
}
