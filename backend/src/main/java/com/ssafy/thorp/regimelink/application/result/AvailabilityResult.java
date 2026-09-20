package com.ssafy.thorp.regimelink.application.result;

import com.ssafy.thorp.regimelink.application.port.BundleRunView;
import com.ssafy.thorp.regimelink.domain.model.Availability;
import com.ssafy.thorp.regimelink.domain.model.AvailableRange;
import com.ssafy.thorp.regimelink.domain.model.InstrumentHistory;
import com.ssafy.thorp.regimelink.domain.model.PostWindow;
import com.ssafy.thorp.regimelink.domain.vo.CaseRef;
import com.ssafy.thorp.regimelink.domain.vo.FilterKey;
import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.List;

/**
 * 표시 후보에 가용성 판정을 붙인 결과 (contracts §1).
 *
 * <p><b>유지율과 초과수익은 여기 없다.</b> 유지율은 기록 전용이고(FR-021) 초과수익은 011이 내보내지 않는 값이라, 담을 자리를 만들지 않는 것으로 막는다.
 */
public record AvailabilityResult(
        Search search, Bundle bundle, Filter filter, List<CaseAvailability> cases) {

    public AvailabilityResult {
        cases = cases == null ? List.of() : List.copyOf(cases);
    }

    /**
     * 어느 검색을 읽었나.
     *
     * <p>{@code snapshotId}(010 행의 PK)는 담지 않는다 — 내부 PK를 노출하지 않는다(ADR-0002 D2).
     */
    public record Search(
            String snapshotKey,
            String mode,
            String fieldId,
            YearMonth baselineMonth,
            String status) {}

    /** 비교 묶음과 그 종목 이력. 화면이 "왜 이 사례를 열 수 없는지"를 설명할 근거다. */
    public record Bundle(List<BundleRunView> runs, List<InstrumentHistory> instruments) {

        public Bundle {
            runs = runs == null ? List.of() : List.copyOf(runs);
            instruments = instruments == null ? List.of() : List.copyOf(instruments);
        }
    }

    /**
     * 공통 가용 범위 필터.
     *
     * <p>{@code MARKET_FLOW} 경로면 필터를 계산하지 않으므로 {@code applied=false}이고 키·범위·제외 수가 비어 있다(contracts
     * §1). 그때도 판정은 붙어 결과 자리에 사유가 보인다.
     */
    public record Filter(
            boolean applied,
            FilterKey key,
            AvailableRange availableRange,
            Integer excludedCount,
            int minPostTradingDays) {

        public static Filter notApplied(int minPostTradingDays) {
            return new Filter(false, null, null, null, minPostTradingDays);
        }
    }

    /**
     * 사례 하나에 붙은 판정.
     *
     * @param availability 비교 묶음 전체의 판정. 가장 나쁜 실행이 이긴다
     * @param perRun 실행별 판정. 대응표 누락처럼 한 실행에만 걸리는 사유를 화면이 구분한다
     * @param shownInFiltered 기본 목록에 그리는가. 필터를 걸지 않는 경로에서는 언제나 참이다
     */
    public record CaseAvailability(
            CaseRef caseRef,
            int rank,
            BigDecimal distance,
            PostWindow postWindow,
            Availability availability,
            List<RunAvailability> perRun,
            boolean shownInFiltered) {

        public CaseAvailability {
            perRun = perRun == null ? List.of() : List.copyOf(perRun);
        }

        public YearMonth endMonth() {
            return caseRef.endMonth();
        }
    }

    /** 실행 하나의 판정. 공개 토큰으로 가리킨다 — 실행 PK를 내보내지 않는다. */
    public record RunAvailability(String publicToken, Availability availability) {}
}
