package com.ssafy.thorp.common.web.response;

import org.springframework.http.HttpStatus;

/** 신규 API 명세의 응답 형식. 기존 ApiResponse 사용처는 별도 마이그레이션 대상이다. */
public record HttpApiResponse<T>(
        String httpStatus, boolean isSuccess, String message, int code, T result) {
    public static <T> HttpApiResponse<T> ok(T result) {
        return new HttpApiResponse<>("OK", true, "요청이 성공적으로 처리되었습니다.", 200, result);
    }

    public static <T> HttpApiResponse<T> fail(HttpStatus status, String message) {
        return new HttpApiResponse<>(status.name(), false, message, status.value(), null);
    }
}
