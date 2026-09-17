package com.ssafy.thorp.regime.domain.vo;

/**
 * 기준월을 어디서 얻었는가 (data-model §5).
 *
 * <p><b>결과의 성질이 아니라 요청의 성질이다.</b> 그래서 식별 키에 들어가지 않고 snapshot에 저장되지도 않는다 — 같은 월이면 T_g로 얻었든 사용자가 지정했든
 * 계산에 들어가는 값이 같아 결과가 같다(spec FR-029). 조회가 <b>요청을 보고 그때 판단</b>한다.
 */
public enum BaselineOrigin {

    /** 분야 검색에서 그 분야의 T_g를 썼다. */
    T_G,

    /** 종합 검색에서 T_all을 썼다. */
    T_ALL,

    /** 사용자가 {@code baselineMonth}를 직접 지정했다. */
    USER_SPECIFIED;

    /** 요청을 보고 정한다 — {@code baselineMonth}가 오면 지정, 없으면 모드가 정한다 (계약 §1). */
    public static BaselineOrigin of(SearchMode mode, boolean baselineMonthGiven) {
        if (baselineMonthGiven) {
            return USER_SPECIFIED;
        }
        return mode == SearchMode.COMPOSITE ? T_ALL : T_G;
    }
}
