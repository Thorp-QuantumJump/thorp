package com.ssafy.thorp.regimelink.support;

import com.ssafy.thorp.regimelink.application.repository.CaseCandidate;
import com.ssafy.thorp.regimelink.application.repository.RegimeSearchReadRepository;
import com.ssafy.thorp.regimelink.application.repository.RegimeSearchView;
import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** 010 검색 조회의 시험용 구현. 표시 후보를 그대로 들고 있는다. */
public class FakeRegimeSearches implements RegimeSearchReadRepository {

    private RegimeSearchView view;

    public static RegimeSearchView view(
            String snapshotKey, YearMonth baselineMonth, String status, YearMonth... endMonths) {
        List<CaseCandidate> cases = new ArrayList<>();
        int rank = 1;
        for (YearMonth endMonth : endMonths) {
            cases.add(
                    new CaseCandidate(
                            endMonth, rank, new BigDecimal("0.4" + String.format("%04d", rank))));
            rank++;
        }
        return new RegimeSearchView(
                1L, snapshotKey, "FIELD", "LABOR", baselineMonth, status, cases);
    }

    public FakeRegimeSearches holding(RegimeSearchView view) {
        this.view = view;
        return this;
    }

    @Override
    public Optional<RegimeSearchView> findLatest(
            String mode, String fieldId, YearMonth baselineMonth) {
        return Optional.ofNullable(view);
    }

    @Override
    public Optional<RegimeSearchView> findByKey(String snapshotKey) {
        return Optional.ofNullable(view).filter(found -> found.snapshotKey().equals(snapshotKey));
    }
}
