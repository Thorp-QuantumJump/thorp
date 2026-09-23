package com.ssafy.thorp.result.application.result;

/**
 * 결과를 어디까지 검증했다고 말할 수 있는가(spec 005 FR-018, PD-12).
 *
 * <p><b>PD-12가 확정되기 전에는 {@link #UNVALIDATED} 하나뿐이다.</b> '검증 통과'가 아니라 '말할 수 없음'이라는 뜻이다. 단계 이름을 미리
 * 만들어 두면 아무도 판정하지 않은 값이 응답에 실릴 길이 생긴다.
 */
public enum ValidationTier {
    UNVALIDATED
}
