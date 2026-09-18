package com.ssafy.thorp.regime.application.result;

import com.ssafy.thorp.regime.application.port.IndicatorRegistration;
import com.ssafy.thorp.regime.domain.model.RegimeCase;
import com.ssafy.thorp.regime.domain.model.RegimeSnapshot;
import java.util.Map;

/**
 * 사례 상세 조회 결과 (계약 §3).
 *
 * <p><b>어느 검색의 사례인지</b>가 함께 있어야 뜻이 선다 — 기준월이 다르면 같은 {@code endMonth}라도 z와 거리가 다르다. 그래서 snapshot을 함께
 * 들고 다니며, 수기 대조 입력인 공통 척도도 거기서 나온다(spec FR-028, SC-003).
 */
public record RegimeCaseResult(
        RegimeSnapshot snapshot,
        RegimeCase regimeCase,
        Map<String, IndicatorRegistration> indicators) {}
