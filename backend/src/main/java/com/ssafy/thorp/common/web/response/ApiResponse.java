package com.ssafy.thorp.common.web.response;

/**
 * 모든 API 응답의 공통 포맷.
 *
 * <p>성공: {@code { success: true, data: ..., error: null }} / 실패: {@code { success: false, data:
 * null, error: { code, message } }}
 */
public record ApiResponse<T>(boolean success, T data, ErrorResponse error) {

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(true, data, null);
    }

    public static <T> ApiResponse<T> fail(ErrorResponse error) {
        return new ApiResponse<>(false, null, error);
    }
}
