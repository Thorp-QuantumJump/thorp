package com.ssafy.thorp.regime.application.result;

import com.ssafy.thorp.regime.application.port.FieldRegistration;
import com.ssafy.thorp.regime.application.port.IndicatorRegistration;
import com.ssafy.thorp.regime.domain.model.RegimeCase;
import com.ssafy.thorp.regime.domain.model.RegimeSnapshot;
import com.ssafy.thorp.regime.domain.vo.BaselineOrigin;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 검색 하나의 조회 결과 (계약 §1).
 *
 * <p>저장된 {@link RegimeSnapshot}에 <b>요청의 성질</b>({@code baselineOrigin})과 <b>표시용 이름</b>(분야·지표)을 얹은
 * 것이다. 둘 다 snapshot에 저장하지 않는다 — 근거는 요청마다 다르고, 이름은 008 등록표의 최신 값을 그대로 보이는 편이 맞다.
 *
 * <p>{@code displayedCases}는 {@code limit}만큼만 담는다. <b>잘라 낸 수를 세지 않는다</b> — 화면은 {@code stored}와
 * {@code displayed}로 '더 보기' 노출을 정하고 10을 가정하지 않는다(spec FR-013).
 */
public record RegimeSearchResult(
        RegimeSnapshot snapshot,
        BaselineOrigin baselineOrigin,
        FieldRegistration field,
        Map<String, IndicatorRegistration> indicators,
        List<RegimeCase> displayedCases) {

    /** 분야 검색이면 그 분야, 종합이면 비어 있다. */
    public Optional<FieldRegistration> fieldRegistration() {
        return Optional.ofNullable(field);
    }

    /** 이 응답에 담긴 사례 수 (계약 §1의 {@code counts.displayed}). */
    public int displayedCount() {
        return displayedCases.size();
    }
}
