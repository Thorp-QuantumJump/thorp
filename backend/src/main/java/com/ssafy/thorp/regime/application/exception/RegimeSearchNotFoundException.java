package com.ssafy.thorp.regime.application.exception;

/**
 * 그 검색이 없다 (계약 §1 오류표).
 *
 * <p>404가 되는 경우는 둘뿐이다 — 등록표에 없는 분야, 그리고 <b>자료 범위 밖의 기준월</b>. 자료 범위 안이지만 척도가 나오지 않는 경우는 404가 아니라 200
 * + {@code NOT_COMPUTABLE}이다(T098). "자료가 없다"와 "계산할 수 없다"는 다른 말이고, 후자는 사용자가 이해할 수 있는 결과다.
 *
 * <p>{@code searchId}로 지목한 항목이 없을 때는 <b>철회된 것과 정리된 것을 구분해</b> 알린다(spec FR-035).
 */
public class RegimeSearchNotFoundException extends RuntimeException {

    public RegimeSearchNotFoundException(String message) {
        super(message);
    }
}
