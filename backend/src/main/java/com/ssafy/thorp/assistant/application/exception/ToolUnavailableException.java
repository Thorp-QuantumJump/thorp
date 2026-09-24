package com.ssafy.thorp.assistant.application.exception;

/**
 * 도구를 실행할 수 없다는 신호(FR-007). 오류가 아니라 모델에 "사용할 수 없음: {이유}"로 돌아가는 정상 흐름이라 {@code BusinessException}이
 * 아니다. 이유에는 토큰·식별자·원문을 넣지 않는다 — 그대로 모델에 간다.
 */
public class ToolUnavailableException extends RuntimeException {
    public ToolUnavailableException(String reason) {
        super(reason);
    }
}
