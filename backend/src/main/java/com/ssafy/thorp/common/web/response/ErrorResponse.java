package com.ssafy.thorp.common.web.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.ssafy.thorp.common.exception.ErrorCode;
import java.util.Map;

/**
 * 실패 응답의 {@code error} 필드.
 *
 * @param details 화면이 판단에 쓰는 추가 정보(예: 재시도까지 남은 초). 없으면 <b>키가 생략된다</b> — 전역 {@code NON_NULL} 은 다른 계약을
 *     깨므로 이 필드에만 건다
 */
public record ErrorResponse(
        String code,
        String message,
        @JsonInclude(JsonInclude.Include.NON_NULL) Map<String, Object> details) {

    /** 추가 정보가 없는 응답. 도메인별 핸들러가 문자열 코드로 직접 만들 때 쓴다. */
    public ErrorResponse(String code, String message) {
        this(code, message, null);
    }

    public static ErrorResponse of(ErrorCode errorCode) {
        return new ErrorResponse(errorCode.name(), errorCode.getMessage(), null);
    }

    public static ErrorResponse of(ErrorCode errorCode, String message) {
        return new ErrorResponse(errorCode.name(), message, null);
    }

    public static ErrorResponse of(
            ErrorCode errorCode, String message, Map<String, Object> details) {
        return new ErrorResponse(errorCode.name(), message, details);
    }
}
