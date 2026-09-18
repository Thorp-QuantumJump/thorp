package com.ssafy.thorp.regime.presentation.response;

import com.ssafy.thorp.regime.application.port.IndicatorRegistration;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Map;

/**
 * 응답 표기를 한 자리에서 맞춘다.
 *
 * <p>월은 {@code YYYY-MM}, 날짜는 ISO다. <b>문자열로 직접 만든다</b> — Jackson의 시간 모듈 설정에 맡기면 그 설정이 바뀐 날 계약이 조용히
 * 달라지고, 그 변화는 다른 도메인의 응답까지 함께 건드린다.
 *
 * <p>지표 이름은 등록표에 없으면 {@code null}이다. <b>"(알 수 없음)" 같은 대체 문구를 지어내지 않는다</b> — 등록표에서 빠진 지표와 오타 난 id가
 * 화면에서 같아 보이면 그 차이를 되짚을 곳이 사라진다.
 */
final class RegimeFormat {

    private RegimeFormat() {}

    static String month(YearMonth value) {
        return value == null ? null : value.toString();
    }

    static String date(LocalDate value) {
        return value == null ? null : value.toString();
    }

    static String name(Map<String, IndicatorRegistration> names, String indicatorId) {
        IndicatorRegistration registration = names.get(indicatorId);
        return registration == null ? null : registration.name();
    }

    static String unit(Map<String, IndicatorRegistration> names, String indicatorId) {
        IndicatorRegistration registration = names.get(indicatorId);
        return registration == null ? null : registration.unit();
    }
}
