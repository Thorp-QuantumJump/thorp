package com.ssafy.thorp.common.exception;

import org.springframework.http.HttpStatus;

/**
 * 비즈니스 예외의 종류와 HTTP 상태 매핑.
 *
 * <p>응답의 {@code error.code}에는 enum 이름이 그대로 실린다. 도메인별 세부 코드가 필요해지면 여기에 추가한다.
 */
public enum ErrorCode {
    INVALID_INPUT(HttpStatus.BAD_REQUEST, "입력값이 올바르지 않습니다."),
    RESOURCE_NOT_FOUND(HttpStatus.NOT_FOUND, "요청한 리소스를 찾을 수 없습니다."),
    /** 리소스는 있지만 현재 상태에서는 요청을 처리할 수 없다. 예: COMPLETED가 아닌 백테스트의 결과 조회. */
    RESULT_NOT_READY(HttpStatus.CONFLICT, "백테스트 결과가 아직 준비되지 않았습니다."),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "서버 내부 오류가 발생했습니다.");

    private final HttpStatus status;
    private final String message;

    ErrorCode(HttpStatus status, String message) {
        this.status = status;
        this.message = message;
    }

    public HttpStatus getStatus() {
        return status;
    }

    /** 클라이언트에 노출되는 기본 메시지. */
    public String getMessage() {
        return message;
    }
}
