package com.thorp.api.common.web.response;

import com.thorp.api.common.exception.ErrorCode;

/** 실패 응답의 {@code error} 필드. */
public record ErrorResponse(String code, String message) {

    public static ErrorResponse of(ErrorCode errorCode) {
        return new ErrorResponse(errorCode.name(), errorCode.getMessage());
    }

    public static ErrorResponse of(ErrorCode errorCode, String message) {
        return new ErrorResponse(errorCode.name(), message);
    }
}
