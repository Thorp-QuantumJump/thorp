package com.ssafy.thorp.regime.application.exception;

/**
 * 저장된 검색 결과를 <b>읽지 못했다</b> (spec FR-034).
 *
 * <p>계산 불가와 다르다. 계산 불가는 "다시 와도 같은 것"(척도가 0이거나 자료가 없다)이고 이것은 "다시 오면 달라질 수 있는 것"이다. 둘을 같은 화면으로 말하면
 * 사용자는 기다려야 할 때 포기하고, 포기해야 할 때 계속 기다린다.
 *
 * <p><b>빈 결과나 {@code NOT_COMPUTABLE}로 바꾸지 않는다.</b> 읽지 못한 것을 "결과가 없다"로 내보내면 실패가 정상 화면 안에 숨는다.
 */
public class RegimeLookupFailedException extends RuntimeException {

    public RegimeLookupFailedException(String message, Throwable cause) {
        super(message, cause);
    }
}
