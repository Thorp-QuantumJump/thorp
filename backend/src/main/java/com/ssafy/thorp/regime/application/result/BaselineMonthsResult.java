package com.ssafy.thorp.regime.application.result;

import com.ssafy.thorp.regime.application.port.FieldRegistration;
import java.time.YearMonth;
import java.util.List;

/**
 * 화면이 기준월을 고를 때 쓰는 목록 (계약 §5).
 *
 * <p><b>값의 출처가 둘이다</b> — 분야 <b>이름</b>은 {@code MacroIndicatorPort}(008 등록표)가, <b>기준월과 범위</b>는 {@code
 * regime_baseline_month} 표가 준다. 기준월을 008에서 직접 읽지 않는 이유는 계약 §0.2다: 008이 올린 최신 T_g는 배치가 아직 계산하지 않았을 수
 * 있고, 그 달로 검색하면 404가 난다.
 *
 * <p>{@code composite}가 {@code null}이면 종합 기준월이 아직 없다는 뜻이다. 그때도 분야 검색은 그대로 제공한다(spec FR-039).
 */
public record BaselineMonthsResult(
        List<FieldBaseline> byField, YearMonth composite, YearMonth earliest, YearMonth latest) {

    /** 분야 하나의 이름과 기준월. 이름은 등록표에서, 기준월은 배치가 남긴 표에서 왔다. */
    public record FieldBaseline(FieldRegistration field, YearMonth baselineMonth) {}
}
