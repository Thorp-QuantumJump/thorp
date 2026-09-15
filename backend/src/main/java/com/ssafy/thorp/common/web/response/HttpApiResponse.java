package com.ssafy.thorp.common.web.response;

import org.springframework.http.HttpStatus;

/** 신규 API 명세의 응답 형식. 기존 ApiResponse 사용처는 별도 마이그레이션 대상이다. */
public record HttpApiResponse<T>(
        String httpStatus, boolean isSuccess, String message, int code, T result) {
    public static <T> HttpApiResponse<T> ok(T result) {
        return new HttpApiResponse<>("OK", true, "요청이 성공적으로 처리되었습니다.", 200, result);
    }

    /**
     * 그 엔드포인트만의 성공 문구를 쓰는 경우.
     *
     * <p>문구가 계약에 적혀 있으면 그것을 따른다. 기본 문구로 덮으면 화면이 계약을 보고 준비한 안내와 서버가 보내는 말이 어긋난다.
     */
    public static <T> HttpApiResponse<T> ok(String message, T result) {
        return new HttpApiResponse<>("OK", true, message, 200, result);
    }

    public static <T> HttpApiResponse<T> fail(HttpStatus status, String message) {
        return new HttpApiResponse<>(status.name(), false, message, status.value(), null);
    }
}
