package com.ssafy.thorp.regimelink.presentation.response;

import com.ssafy.thorp.regimelink.application.result.CaseWindowResult;
import com.ssafy.thorp.regimelink.domain.model.PostWindow;
import java.time.LocalDate;

/**
 * 사례 구간 조회 응답 (contracts §1a).
 *
 * <p>비교 묶음이 없으므로 판정이 붙지 않는다 — 여기 있는 값은 시장 달력만으로 정해진다. 003이 분석 기간의 상한·하한을 잡는 데 쓴다.
 *
 * <p>목록(§1)과 달리 {@code lastAvailable}을 싣는다. 003은 종료일 하한을 정해야 하는데, 사후 구간이 미완성이면 {@code d63}이 없어 그 값이
 * 하한이 되기 때문이다.
 */
public record CaseWindowResponse(
        AvailabilityResponse.Basis basis, String caseId, String endMonth, Window postWindow) {

    /** {@code d21}·{@code d63}은 판정 기준일을 넘어가면 명시적 {@code null}이다. */
    public record Window(
            LocalDate d0,
            LocalDate d21,
            LocalDate d63,
            LocalDate lastAvailable,
            int completedTradingDays,
            boolean complete21,
            boolean complete63) {}

    public static CaseWindowResponse from(CaseWindowResult result) {
        PostWindow window = result.postWindow();
        return new CaseWindowResponse(
                new AvailabilityResponse.Basis("HISTORICAL_REVISION"),
                result.caseRef().toString(),
                result.caseRef().endMonth().toString(),
                new Window(
                        window.d0(),
                        window.d21(),
                        window.d63(),
                        window.lastAvailable(),
                        window.completedTradingDays(),
                        window.complete21(),
                        window.complete63()));
    }
}
